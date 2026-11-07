package com.firefly.governance;

import com.firefly.engine.ExecutionCommand;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.EnqueueAdmissionResult;
import com.firefly.store.JobRepository;
import com.firefly.store.SchedulingAdvance;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adds one durable backlog reservation to every accepted outbox item.
 *
 * <p>The wrapper is intentionally placed at the repository boundary so scheduled, manual,
 * event, backfill and retry producers cannot bypass the same cluster-wide quota.</p>
 */
public final class TenantQuotaAwareJobRepository implements InvocationHandler {
    public static final Duration DEFAULT_BACKLOG_LEASE = Duration.ofDays(36_500);

    private final JobRepository delegate;
    private final TenantQuotaStore quotas;
    private final Clock clock;
    private final SchedulerMetrics metrics;
    private final Duration backlogLease;

    private TenantQuotaAwareJobRepository(
            JobRepository delegate,
            TenantQuotaStore quotas,
            Clock clock,
            SchedulerMetrics metrics,
            Duration backlogLease
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.backlogLease = Objects.requireNonNull(backlogLease, "backlogLease");
        if (backlogLease.isZero() || backlogLease.isNegative()) {
            throw new IllegalArgumentException("backlogLease must be positive");
        }
    }

    public static JobRepository wrap(
            JobRepository delegate,
            TenantQuotaStore quotas,
            Clock clock,
            SchedulerMetrics metrics
    ) {
        return wrap(delegate, quotas, clock, metrics, DEFAULT_BACKLOG_LEASE);
    }

    public static JobRepository wrap(
            JobRepository delegate,
            TenantQuotaStore quotas,
            Clock clock,
            SchedulerMetrics metrics,
            Duration backlogLease
    ) {
        return (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                new TenantQuotaAwareJobRepository(delegate, quotas, clock, metrics, backlogLease)
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "TenantQuotaAwareJobRepository[" + delegate + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(delegate, args);
            };
        }
        return switch (method.getName()) {
            case "enqueueManual" -> enqueueManual((ExecutionCommand) args[0]);
            case "enqueueManualWithAdmission" -> enqueueManualWithAdmission((ExecutionCommand) args[0]);
            case "advanceAndEnqueue" -> advanceAndEnqueue(
                    (String) args[0], (Instant) args[1], (Instant) args[2], castCommands(args[3])
            );
            case "advanceAndEnqueueBatch" -> advanceAndEnqueueBatch(castAdvances(args[0]));
            case "scheduleExecutionRetry" -> scheduleExecutionRetry(
                    (String) args[0], (Instant) args[1], (boolean) args[2]
            );
            case "requeueDeadDispatch" -> requeueDeadDispatch((String) args[0], (Instant) args[1]);
            default -> invokeDelegate(method, args);
        };
    }

    private boolean enqueueManual(ExecutionCommand command) {
        return enqueueManualWithAdmission(command).accepted();
    }

    private EnqueueAdmissionResult enqueueManualWithAdmission(ExecutionCommand command) {
        Admission admission = reserve(command);
        if (!admission.admitted()) return EnqueueAdmissionResult.rejected(admission.reason());
        try {
            boolean queued = delegate.enqueueManual(command);
            if (!queued) cleanupUnlessActive(admission, command.executionId());
            if (queued) return EnqueueAdmissionResult.acceptedResult();
            return delegate.findDispatch(command.executionId()).isPresent()
                    ? EnqueueAdmissionResult.duplicateResult()
                    : EnqueueAdmissionResult.rejected(TenantAdmissionReason.OUTBOX_REJECTED);
        } catch (RuntimeException failure) {
            cleanupUnlessActive(admission, command.executionId());
            throw failure;
        }
    }

    private boolean advanceAndEnqueue(
            String jobId, Instant expected, Instant next, List<ExecutionCommand> commands
    ) {
        if (commands.isEmpty()) return delegate.advanceAndEnqueue(jobId, expected, next, commands);
        Admissions admissions = reserveAll(commands);
        if (!admissions.admitted()) return false;
        try {
            boolean advanced = delegate.advanceAndEnqueue(jobId, expected, next, commands);
            if (!advanced) cleanupUnlessActive(admissions);
            return advanced;
        } catch (RuntimeException failure) {
            cleanupUnlessActive(admissions);
            throw failure;
        }
    }

    private List<Boolean> advanceAndEnqueueBatch(List<SchedulingAdvance> advances) {
        if (advances.isEmpty()) return List.of();
        List<Boolean> results = new ArrayList<>(java.util.Collections.nCopies(advances.size(), false));
        List<SchedulingAdvance> accepted = new ArrayList<>();
        List<Integer> acceptedIndexes = new ArrayList<>();
        List<Admissions> reservations = new ArrayList<>();
        for (int index = 0; index < advances.size(); index++) {
            SchedulingAdvance advance = advances.get(index);
            Admissions admission = reserveAll(advance.commands());
            if (!admission.admitted()) continue;
            accepted.add(advance);
            acceptedIndexes.add(index);
            reservations.add(admission);
        }
        if (accepted.isEmpty()) return List.copyOf(results);
        try {
            List<Boolean> delegated = delegate.advanceAndEnqueueBatch(accepted);
            if (delegated.size() != accepted.size()) {
                throw new IllegalStateException("batch scheduling result size does not match request size");
            }
            for (int index = 0; index < delegated.size(); index++) {
                boolean advanced = delegated.get(index);
                results.set(acceptedIndexes.get(index), advanced);
                if (!advanced) cleanupUnlessActive(reservations.get(index));
            }
            return List.copyOf(results);
        } catch (RuntimeException failure) {
            reservations.forEach(this::cleanupUnlessActive);
            throw failure;
        }
    }

    private boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout) {
        var source = delegate.findDispatch(sourceExecutionId).orElse(null);
        if (source == null) return delegate.scheduleExecutionRetry(sourceExecutionId, requestedAt, timeout);
        var policy = source.command().definition().retryPolicy();
        int nextAttempt = source.command().runAttempt() + 1;
        boolean allowed = nextAttempt < policy.maxAttempts()
                && (timeout ? policy.retryOnTimeout() : policy.retryOnFailure());
        if (!allowed) return delegate.scheduleExecutionRetry(sourceExecutionId, requestedAt, timeout);
        String executionId = source.command().rootExecutionId() + "@attempt:" + nextAttempt;
        ExecutionCommand retry = new ExecutionCommand(
                executionId, source.command().rootExecutionId(), nextAttempt,
                source.command().definition(), source.command().scheduledFireTime(), requestedAt,
                source.command().ownerNodeId(), source.command().fencingToken(), source.command().traceCarrier()
        );
        Admission admission = reserve(retry);
        if (!admission.admitted()) return false;
        try {
            boolean scheduled = delegate.scheduleExecutionRetry(sourceExecutionId, requestedAt, timeout);
            if (!scheduled) cleanupUnlessActive(admission, executionId);
            return scheduled;
        } catch (RuntimeException failure) {
            cleanupUnlessActive(admission, executionId);
            throw failure;
        }
    }

    private boolean requeueDeadDispatch(String outboxId, Instant now) {
        var record = delegate.findDispatch(outboxId).orElse(null);
        if (record == null || record.status() != DispatchOutboxStatus.DEAD) {
            return delegate.requeueDeadDispatch(outboxId, now);
        }
        Admission admission = reserve(record.command());
        if (!admission.admitted()) return false;
        try {
            boolean requeued = delegate.requeueDeadDispatch(outboxId, now);
            if (!requeued) cleanupUnlessActive(admission, outboxId);
            return requeued;
        } catch (RuntimeException failure) {
            cleanupUnlessActive(admission, outboxId);
            throw failure;
        }
    }

    private Admissions reserveAll(List<ExecutionCommand> commands) {
        if (commands.isEmpty()) return new Admissions(true, List.of());
        List<Admission> reservations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExecutionCommand command : commands) {
            if (!seen.add(command.executionId())) continue;
            Admission admission = reserve(command);
            if (!admission.admitted()) {
                reservations.forEach(value -> cleanupUnlessActive(value, value.executionId()));
                return new Admissions(false, List.of());
            }
            reservations.add(admission);
        }
        return new Admissions(true, List.copyOf(reservations));
    }

    private Admission reserve(ExecutionCommand command) {
        Instant now = clock.instant();
        String reservationId = TenantReservationKeys.backlog(command.executionId());
        TenantAdmissionDecision decision = quotas.tryReserve(new TenantAdmissionRequest(
                reservationId, command.definition().tenantId(), 0, 0, 0, 1, 0
        ), now, backlogLease);
        if (!decision.admitted()) {
            metrics.recordTenantAdmissionRejection(command.definition().tenantId(), decision.reason());
            return new Admission(command.executionId(), reservationId, false, false, decision.reason());
        }
        return new Admission(command.executionId(), reservationId, true,
                decision.reason() == TenantAdmissionReason.ADMITTED, decision.reason());
    }

    private void cleanupUnlessActive(Admissions admissions) {
        admissions.reservations().forEach(value -> cleanupUnlessActive(value, value.executionId()));
    }

    private void cleanupUnlessActive(Admission admission, String executionId) {
        if (!admission.newReservation()) return;
        boolean active = delegate.findDispatch(executionId)
                .map(record -> record.status() != DispatchOutboxStatus.DONE
                        && record.status() != DispatchOutboxStatus.DEAD)
                .orElse(false);
        if (!active) quotas.release(admission.reservationId(), clock.instant());
    }

    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ExecutionCommand> castCommands(Object value) {
        return (List<ExecutionCommand>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<SchedulingAdvance> castAdvances(Object value) {
        return (List<SchedulingAdvance>) value;
    }

    private record Admission(String executionId, String reservationId,
                             boolean admitted, boolean newReservation,
                             TenantAdmissionReason reason) { }
    private record Admissions(boolean admitted, List<Admission> reservations) { }
}
