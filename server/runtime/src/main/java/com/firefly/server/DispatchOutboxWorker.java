package com.firefly.server;

import com.firefly.engine.JobDispatcher;
import com.firefly.engine.DispatchSubmission;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchType;
import com.firefly.store.JobRepository;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.lifecycle.ManagedWorker;
import com.firefly.tracing.FireflyTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reliably hands committed outbox records to local or remote dispatchers. */
public final class DispatchOutboxWorker implements AutoCloseable {
    private static final Logger log = Logger.getLogger(DispatchOutboxWorker.class.getName());
    private final String nodeId;
    private final JobRepository repository;
    private final JobDispatcher dispatcher;
    private final Clock clock;
    private final Set<DispatchType> dispatchTypes;
    private final SchedulerMetrics metrics;
    private final DispatchOutboxOptions options;
    private final java.util.function.Predicate<com.firefly.engine.ExecutionCommand> dispatchEligibility;
    private volatile java.util.function.BooleanSupplier claimAdmission = () -> true;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-dispatch-outbox");
        thread.setDaemon(false);
        return thread;
    });

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, new SchedulerMetrics());
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, metrics, DispatchOutboxOptions.defaults());
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics,
            DispatchOutboxOptions options
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, metrics, options, ignored -> true);
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics,
            DispatchOutboxOptions options,
            java.util.function.Predicate<com.firefly.engine.ExecutionCommand> dispatchEligibility
    ) {
        this.nodeId = nodeId;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.dispatchTypes = Set.copyOf(dispatchTypes);
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.dispatchEligibility = java.util.Objects.requireNonNull(dispatchEligibility, "dispatchEligibility");
        if (this.dispatchTypes.isEmpty()) {
            throw new IllegalArgumentException("dispatchTypes must not be empty");
        }
    }

    public void start() {
        timer.scheduleWithFixedDelay(
                this::safeDrain, 0, options.pollInterval().toMillis(), TimeUnit.MILLISECONDS
        );
    }

    public void setClaimAdmission(java.util.function.BooleanSupplier claimAdmission) {
        this.claimAdmission = java.util.Objects.requireNonNull(claimAdmission, "claimAdmission");
    }

    void drain() {
        if (!claimAdmission.getAsBoolean()) return;
        Instant now = clock.instant();
        for (DispatchOutboxRecord record : claimDispatches(now)) {
            Span span = FireflyTelemetry.tracer().spanBuilder("firefly.outbox.dispatch")
                    .setParent(FireflyTelemetry.extract(record.command().traceCarrier()))
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("firefly.phase", "outbox")
                    .setAttribute("firefly.execution.id", record.command().executionId())
                    .setAttribute("firefly.job.id", record.command().definition().id())
                    .setAttribute("firefly.node.id", nodeId)
                    .setAttribute("firefly.run.attempt", record.command().runAttempt())
                    .setAttribute("firefly.outbox.attempt", record.attempt())
                    .setAttribute("firefly.outbox.age.ms", Math.max(0L,
                            Duration.between(record.command().dispatchTime(), now).toMillis()))
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                metrics.observeOutboxAge(Duration.between(record.command().dispatchTime(), now));
                if (record.attempt() > options.maxAttempts()) {
                    boolean dead = repository.retryClaimedDispatchAfter(
                            record.outboxId(), nodeId, record.attempt(), Duration.ZERO,
                            "maximum delivery attempts exceeded", options.maxAttempts()
                    );
                    if (dead) metrics.recordOutboxDeliveryExhaustion();
                    span.setStatus(StatusCode.ERROR, "maximum delivery attempts exceeded");
                    continue;
                }
                if (!dispatchEligibility.test(record.command())) {
                    repository.deferClaimedDispatch(
                            record.outboxId(), nodeId, record.attempt(), options.pollInterval(),
                            "no local executor route on gateway " + nodeId
                    );
                    span.addEvent("route.deferred");
                    continue;
                }
                dispatch(record, span);
            } finally {
                span.end();
            }
        }
    }

    private List<DispatchOutboxRecord> claimDispatches(Instant now) {
        Span span = FireflyTelemetry.tracer().spanBuilder("firefly.outbox.claim")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("firefly.phase", "database")
                .setAttribute("db.operation.name", "firefly.outbox.claim")
                .setAttribute("firefly.node.id", nodeId)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            List<DispatchOutboxRecord> records = repository.claimDispatches(
                    nodeId, now, options.claimBatchSize(), options.claimDuration(), dispatchTypes
            );
            span.setAttribute("firefly.outbox.claimed", records.size());
            return records;
        } catch (RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void dispatch(DispatchOutboxRecord record, Span span) {
            try {
                DispatchSubmission submission = dispatcher.submit(record.command().withTraceCarrier(
                        FireflyTelemetry.inject(Context.current())
                ));
                if (!submission.accepted()) {
                    retry(record, "dispatch not accepted");
                    span.setStatus(StatusCode.ERROR, "dispatch not accepted");
                } else if (submission.remote()) {
                    repository.markClaimedDispatchSentFor(
                            record.outboxId(), nodeId, record.attempt(), options.remoteAckTimeout()
                    );
                } else {
                    if (repository.markClaimedDispatchSentFor(
                            record.outboxId(), nodeId, record.attempt(),
                            record.command().definition().timeout()
                    )) {
                        submission.completion().whenComplete((status, error) ->
                        {
                            java.time.Instant completedAt = clock.instant();
                            repository.completeDispatch(record.outboxId(), completedAt);
                            if (status == com.firefly.execution.ExecutionStatus.FAILED || error != null) {
                                repository.scheduleExecutionRetry(record.command().executionId(), completedAt, false);
                            }
                        }
                        );
                    }
                }
            } catch (Exception e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                retry(record, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
    }

    private void retry(DispatchOutboxRecord record, String error) {
        long delayMillis = Math.min(
                options.maxRetryBackoff().toMillis(),
                Math.multiplyExact(1_000L, 1L << Math.min(record.attempt(), 20))
        );
        repository.retryClaimedDispatchAfter(
                record.outboxId(), nodeId, record.attempt(), Duration.ofMillis(delayMillis),
                error, options.maxAttempts()
        );
    }

    private void safeDrain() {
        try {
            drain();
        } catch (Exception e) {
            log.log(Level.SEVERE, "dispatch outbox drain failed", e);
        }
    }

    @Override
    public void close() {
        ManagedWorker.stop(timer, Duration.ofSeconds(5), () -> { }, log);
    }
}
