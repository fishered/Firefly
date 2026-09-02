package com.firefly.engine;

import com.firefly.domain.JobDefinition;
import com.firefly.cluster.ShardLease;
import com.firefly.cluster.ShardHasher;
import com.firefly.cluster.ShardOwnership;
import com.firefly.domain.MisfirePolicy;
import com.firefly.store.JobRepository;
import com.firefly.store.ScheduledJobRecord;
import com.firefly.store.SchedulingAdvance;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.lifecycle.ManagedWorker;
import com.firefly.tracing.FireflyTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.firefly.schedule.SchedulingDecision;
import com.firefly.schedule.SchedulingSemanticsEvaluator;
import com.firefly.schedule.DependencyEvaluator;
import com.firefly.schedule.DependencyStatus;
import com.firefly.schedule.ConditionStatus;
import com.firefly.schedule.SchedulingConditionEvaluator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SchedulerEngine {
    private static final Logger log = Logger.getLogger(SchedulerEngine.class.getName());

    /**
     * Maximum due records advanced in one scheduler tick.
     *
     * <p>This protects the timer thread from unbounded backlog while allowing
     * many different fire-time groups to progress in the same tick.
     */
    private final JobRepository repository;
    private final JobDispatcher dispatcher;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ShardOwnership shardOwnership;
    private final int shardCount;
    private final boolean transactionalOutbox;
    private final SchedulerMetrics metrics;
    private final SchedulerEngineOptions options;
    private final SchedulerTimingIndex timingIndex = new SchedulerTimingIndex();
    private final SchedulingSemanticsEvaluator semanticsEvaluator = new SchedulingSemanticsEvaluator();
    private final SchedulingConditionEvaluator conditionEvaluator;
    private final DependencyEvaluator dependencyEvaluator;
    private final Map<String, Integer> dependencyWaitAttempts = new java.util.HashMap<>();
    private final Map<String, Instant> dependencyBusinessTimes = new java.util.HashMap<>();
    private long loadedConfigurationVersion = Long.MIN_VALUE;
    private Set<Integer> loadedShards = Set.of();
    private Instant lastConfigurationCheck = Instant.MIN;
    private boolean reloadRequired = true;

    public SchedulerEngine(JobRepository repository, JobDispatcher dispatcher, Clock clock) {
        this(repository, dispatcher, clock, () -> Map.of(0, new ShardLease(0, "local", Instant.MAX, 1L)), 1, false);
    }

    public SchedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            int shardCount
    ) {
        this(repository, dispatcher, clock, shardOwnership, shardCount, false);
    }

    public SchedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            int shardCount,
            boolean transactionalOutbox
    ) {
        this(repository, dispatcher, clock, shardOwnership, shardCount, transactionalOutbox,
                new SchedulerMetrics(), SchedulerEngineOptions.defaults(), SchedulingConditionEvaluator.allowAll());
    }

    public SchedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            int shardCount,
            boolean transactionalOutbox,
            SchedulerMetrics metrics
    ) {
        this(repository, dispatcher, clock, shardOwnership, shardCount, transactionalOutbox,
                metrics, SchedulerEngineOptions.defaults(), SchedulingConditionEvaluator.allowAll());
    }

    public SchedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            int shardCount,
            boolean transactionalOutbox,
            SchedulerMetrics metrics,
            SchedulerEngineOptions options
    ) {
        this(repository, dispatcher, clock, shardOwnership, shardCount, transactionalOutbox,
                metrics, options, SchedulingConditionEvaluator.allowAll());
    }

    public SchedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            int shardCount,
            boolean transactionalOutbox,
            SchedulerMetrics metrics,
            SchedulerEngineOptions options,
            SchedulingConditionEvaluator conditionEvaluator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dependencyEvaluator = new DependencyEvaluator(this.repository);
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shardOwnership = Objects.requireNonNull(shardOwnership, "shardOwnership");
        this.shardCount = shardCount;
        this.transactionalOutbox = transactionalOutbox;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.options = Objects.requireNonNull(options, "options");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "firefly-timer");
            thread.setDaemon(false);
            return thread;
        });
    }


    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduleNext(0);
        log.info("firefly started");
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        ManagedWorker.stop(timer, Duration.ofSeconds(5), () -> { }, log);
        log.info("firefly stopped");
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            log.log(Level.SEVERE, "scheduler tick failed", e);
        }
    }

    private void scheduledTick() {
        if (!started.get()) return;
        safeTick();
        if (started.get()) scheduleNext(nextDelayMillis());
    }

    private void scheduleNext(long delayMillis) {
        timer.schedule(this::scheduledTick, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
    }

    private synchronized long nextDelayMillis() {
        Instant nextFireTime = timingIndex.nextFireTime();
        if (nextFireTime == null) return options.maxIdleWakeup().toMillis();
        long delay = Duration.between(clock.instant(), nextFireTime).toMillis();
        return Math.max(1, Math.min(options.maxIdleWakeup().toMillis(), delay));
    }

    public synchronized void tick() {
        Instant now = clock.instant();
        Map<Integer, ShardLease> leases = shardOwnership.ownedShards();
        if (leases.isEmpty()) {
            timingIndex.replace(List.of());
            loadedShards = Set.of();
            return;
        }
        refreshTimingIndex(leases.keySet());
        processDependencyGates(now, leases);
        List<ScheduledJobRecord> dueRecords = timingIndex.pollDue(now, options.maxDueRecordsPerTick());
        try {
            if (transactionalOutbox) {
                processTransactionalBatches(dueRecords, leases, now);
            } else {
                dueRecords.forEach(record -> processRecord(record, leases, now));
            }
        } catch (RuntimeException failure) {
            // pollDue removes records from the local index before persistence completes.
            forceReload();
            throw failure;
        }
        Instant remainingDue = timingIndex.nextFireTime();
        if (dueRecords.size() == options.maxDueRecordsPerTick()
                && remainingDue != null && !remainingDue.isAfter(now)) {
            log.warning("scheduler due backlog reached per-tick limit=" + options.maxDueRecordsPerTick());
            metrics.recordDueBacklog();
        }
    }

    private void processTransactionalBatches(
            List<ScheduledJobRecord> dueRecords,
            Map<Integer, ShardLease> leases,
            Instant now
    ) {
        List<PreparedAdvance> prepared = new ArrayList<>(dueRecords.size());
        for (ScheduledJobRecord record : dueRecords) {
            ShardLease lease = leaseFor(record, leases);
            if (lease == null) {
                forceReload();
                continue;
            }
            prepared.add(prepareAdvance(record, lease, now));
        }
        List<PreparedAdvance> batchable = new ArrayList<>(prepared.size());
        for (PreparedAdvance advance : prepared) {
            if (advance.commands().isEmpty()) {
                SchedulingAdvance cursor = advance.advance();
                boolean updated = repository.updateNextFireTimeWithLease(
                        cursor.jobId(), cursor.expectedCurrentNextFireTime(), cursor.nextFireTime(),
                        advance.lease().ownerNodeId(), advance.lease().fencingToken()
                );
                completeAdvance(advance, updated);
            } else {
                batchable.add(advance);
            }
        }
        for (int from = 0; from < batchable.size(); from += options.schedulingBatchSize()) {
            List<PreparedAdvance> batch = batchable.subList(
                    from, Math.min(batchable.size(), from + options.schedulingBatchSize())
            );
            List<Boolean> results = repository.advanceAndEnqueueBatch(batch.stream()
                    .map(PreparedAdvance::advance)
                    .toList());
            if (results.size() != batch.size()) {
                throw new IllegalStateException("batch scheduling result size does not match request size");
            }
            for (int index = 0; index < batch.size(); index++) {
                completeAdvance(batch.get(index), results.get(index));
            }
        }
    }

    private void processRecord(
            ScheduledJobRecord record,
            Map<Integer, ShardLease> leases,
            Instant now
    ) {
        ShardLease lease = leaseFor(record, leases);
        if (lease == null) {
            forceReload();
            return;
        }
        PreparedAdvance prepared = prepareAdvance(record, lease, now);
        SchedulingAdvance advance = prepared.advance();
        boolean updated = repository.updateNextFireTimeWithLease(
                advance.jobId(), advance.expectedCurrentNextFireTime(), advance.nextFireTime(),
                lease.ownerNodeId(), lease.fencingToken()
        );
        completeAdvance(prepared, updated);
        if (updated) prepared.commands().forEach(dispatcher::dispatch);
    }

    private ShardLease leaseFor(ScheduledJobRecord record, Map<Integer, ShardLease> leases) {
        return leases.get(ShardHasher.shardFor(record.definition().id(), shardCount));
    }

    private PreparedAdvance prepareAdvance(ScheduledJobRecord record, ShardLease lease, Instant now) {
        List<Instant> fireTimes = calculateFireTimes(record, now);
        Instant nextFireTime = calculateNextFireTime(record.definition(), fireTimes, now);
        Instant dispatchTime = clock.instant();
        List<ExecutionCommand> commands = new ArrayList<>();
        boolean waitingDependency = false;
        for (Instant fireTime : fireTimes) {
            SchedulingDecision decision = semantics(record.definition(), fireTime);
            if (decision.decision() == SchedulingDecision.Decision.SKIP) continue;
            String waitKey = record.definition().id() + "@" + fireTime;
            Instant businessTime = dependencyBusinessTimes.getOrDefault(waitKey, fireTime);
            ConditionStatus conditionStatus = conditionStatus(record.definition(), businessTime);
            if (conditionStatus == ConditionStatus.WAITING) {
                waitingDependency = true;
                openDependencyGate(record.definition(), businessTime, now);
                continue;
            }
            if (conditionStatus == ConditionStatus.BLOCKED) continue;
            DependencyStatus dependencyStatus = dependencyStatus(record.definition(), businessTime,
                    Math.max(dependencyWaitAttempts.getOrDefault(waitKey, 0),
                            repository.dependencyWaitAttempts(record.definition().id(), businessTime)));
            if (dependencyStatus == DependencyStatus.WAITING) {
                waitingDependency = true;
                int attempts = Math.max(dependencyWaitAttempts.getOrDefault(waitKey, 0),
                        repository.dependencyWaitAttempts(record.definition().id(), businessTime)) + 1;
                dependencyWaitAttempts.put(waitKey, attempts);
                repository.recordDependencyWait(record.definition().id(), businessTime, attempts, now.plusSeconds(1));
                openDependencyGate(record.definition(), businessTime, now);
                dependencyBusinessTimes.putIfAbsent(record.definition().id() + "@" + nextFireTime, businessTime);
                continue;
            }
            dependencyWaitAttempts.remove(waitKey);
            dependencyBusinessTimes.remove(waitKey);
            repository.clearDependencyWait(record.definition().id(), businessTime);
            if (dependencyStatus == DependencyStatus.BLOCKED) continue;
            commands.add(tracedCommand(record.definition(), decision.fireTime(),
                    decision.decision() == SchedulingDecision.Decision.DELAY ? decision.effectiveTime() : dispatchTime,
                    lease));
        }
        return new PreparedAdvance(
                record,
                new SchedulingAdvance(record.definition().id(), record.nextFireTime(), nextFireTime, commands),
                commands,
                lease
        );
    }

    private void openDependencyGate(JobDefinition definition, Instant businessTime, Instant now) {
        String gateId = definition.id() + "@" + businessTime;
        int configuredAttempts = definition.dependencies().stream()
                .mapToInt(com.firefly.schedule.JobDependency::maxWaitAttempts).max().orElse(300);
        int seconds = Math.max(1, Math.min(86_400, configuredAttempts));
        repository.openDependencyGate(new com.firefly.schedule.DependencyGate(
                gateId, definition.id(), businessTime, now.plusSeconds(1),
                now.plusSeconds(seconds), 0,
                com.firefly.schedule.DependencyGateStatus.WAITING, "prerequisite_pending"));
    }

    private void processDependencyGates(Instant now, Map<Integer, ShardLease> leases) {
        for (com.firefly.schedule.DependencyGate gate : repository.dueDependencyGates(now, options.maxDueRecordsPerTick())) {
            if (!repository.claimDependencyGate(gate.gateId(), gate.waitAttempts(), "scheduler", now.plusSeconds(5))) continue;
            ScheduledJobRecord record = repository.find(gate.jobId()).orElse(null);
            if (record == null) {
                repository.updateDependencyGate(gate.withStatus(com.firefly.schedule.DependencyGateStatus.BLOCKED, "job_deleted"));
                continue;
            }
            ShardLease lease = leaseFor(record, leases);
            if (lease == null) continue;
            ConditionStatus condition = conditionStatus(record.definition(), gate.businessTime());
            DependencyStatus dependency = dependencyStatus(record.definition(), gate.businessTime(), gate.waitAttempts());
            if (condition == ConditionStatus.BLOCKED || dependency == DependencyStatus.BLOCKED) {
                repository.updateDependencyGate(gate.withStatus(com.firefly.schedule.DependencyGateStatus.BLOCKED,
                        condition == ConditionStatus.BLOCKED ? "condition_blocked" : "prerequisite_failed"));
                repository.clearDependencyWait(gate.jobId(), gate.businessTime());
            } else if (condition == ConditionStatus.ALLOWED && dependency == DependencyStatus.ALLOWED) {
                repository.updateDependencyGate(gate.withStatus(com.firefly.schedule.DependencyGateStatus.RELEASED, "prerequisite_satisfied"));
                repository.clearDependencyWait(gate.jobId(), gate.businessTime());
                dispatcher.dispatch(tracedCommand(record.definition(), gate.businessTime(), now, lease));
            } else if (!now.isBefore(gate.deadlineAt())) {
                repository.updateDependencyGate(gate.withStatus(com.firefly.schedule.DependencyGateStatus.EXPIRED, "dependency_deadline_exceeded"));
                repository.clearDependencyWait(gate.jobId(), gate.businessTime());
            } else {
                int attempts = gate.waitAttempts() + 1;
                repository.updateDependencyGate(new com.firefly.schedule.DependencyGate(
                        gate.gateId(), gate.jobId(), gate.businessTime(), now.plusSeconds(1), gate.deadlineAt(),
                        attempts, com.firefly.schedule.DependencyGateStatus.WAITING, "prerequisite_pending"));
                repository.recordDependencyWait(gate.jobId(), gate.businessTime(), attempts, now.plusSeconds(1));
            }
        }
    }

    private DependencyStatus dependencyStatus(JobDefinition definition, Instant fireTime, int waitAttempts) {
        DependencyStatus result = DependencyStatus.ALLOWED;
        for (com.firefly.schedule.JobDependency dependency : definition.dependencies()) {
            DependencyStatus current = dependencyEvaluator.evaluate(
                    dependency, fireTime, waitAttempts);
            if (current == DependencyStatus.BLOCKED) return current;
            if (current == DependencyStatus.WAITING) result = current;
        }
        return result;
    }

    private ConditionStatus conditionStatus(JobDefinition definition, Instant businessTime) {
        ConditionStatus persisted = repository.conditionStatus(definition.id(), businessTime);
        ConditionStatus pluggable = conditionEvaluator.evaluate(definition, businessTime);
        if (persisted == ConditionStatus.BLOCKED || pluggable == ConditionStatus.BLOCKED) {
            return ConditionStatus.BLOCKED;
        }
        if (persisted == ConditionStatus.WAITING || pluggable == ConditionStatus.WAITING) {
            return ConditionStatus.WAITING;
        }
        return ConditionStatus.ALLOWED;
    }

    private SchedulingDecision semantics(JobDefinition definition, Instant fireTime) {
        com.firefly.schedule.CalendarDefinition calendar = definition.calendarId().isBlank()
                ? null : repository.findCalendar(definition.calendarId()).orElse(null);
        return semanticsEvaluator.evaluate(fireTime, calendar, definition.blackoutWindows(), definition.zoneId());
    }

    private ExecutionCommand tracedCommand(
            JobDefinition definition,
            Instant fireTime,
            Instant dispatchTime,
            ShardLease lease
    ) {
        String executionId = executionId(definition, fireTime);
        Span span = FireflyTelemetry.tracer().spanBuilder("firefly.scheduler.schedule")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("firefly.phase", "scheduler")
                .setAttribute("firefly.job.id", definition.id())
                .setAttribute("firefly.execution.id", executionId)
                .setAttribute("firefly.node.id", lease.ownerNodeId())
                .setAttribute("firefly.run.attempt", 0L)
                .setAttribute("firefly.schedule.delay.ms", Math.max(0L,
                        Duration.between(fireTime, dispatchTime).toMillis()))
                .startSpan();
        try {
            return new ExecutionCommand(
                    executionId, definition, fireTime, dispatchTime,
                    lease.ownerNodeId(), lease.fencingToken()
            ).withTraceCarrier(FireflyTelemetry.inject(Context.root().with(span)));
        } finally {
            span.end();
        }
    }

    private void completeAdvance(PreparedAdvance prepared, boolean updated) {
        if (!updated) {
            forceReload();
            return;
        }
        timingIndex.add(new ScheduledJobRecord(
                prepared.record().definition(), prepared.advance().nextFireTime()
        ));
        prepared.commands().forEach(command -> metrics.observeScheduleDelay(
                    Duration.between(command.scheduledFireTime(), command.dispatchTime())
        ));
    }

    private record PreparedAdvance(
            ScheduledJobRecord record,
            SchedulingAdvance advance,
            List<ExecutionCommand> commands,
            ShardLease lease
    ) { }

    private void refreshTimingIndex(Set<Integer> shardIds) {
        Set<Integer> currentShards = Set.copyOf(shardIds);
        Instant now = clock.instant();
        boolean shardsChanged = !currentShards.equals(loadedShards);
        boolean checkConfiguration = now.isBefore(lastConfigurationCheck)
                || !now.isBefore(
                        lastConfigurationCheck.plus(options.configurationRefreshInterval())
                );
        if (!reloadRequired && !shardsChanged && !checkConfiguration) return;

        long configurationVersion = repository.configurationVersion();
        lastConfigurationCheck = now;
        if (reloadRequired || shardsChanged || configurationVersion != loadedConfigurationVersion) {
            timingIndex.replace(repository.listForShards(currentShards, shardCount));
            loadedShards = currentShards;
            loadedConfigurationVersion = configurationVersion;
            reloadRequired = false;
        }
    }

    private void forceReload() {
        reloadRequired = true;
    }

    private List<Instant> calculateFireTimes(ScheduledJobRecord record, Instant now) {
        JobDefinition definition = record.definition();
        Instant dueTime = record.nextFireTime();
        boolean misfired = dueTime.plus(definition.misfireGrace()).isBefore(now);

        if (!misfired) {
            return List.of(dueTime);
        }

        return switch (definition.misfirePolicy()) {
            case SKIP -> List.of();
            case FIRE_ONCE -> List.of(dueTime);
            case CATCH_UP -> calculateCatchUpFireTimes(definition, dueTime, now);
        };
    }

    private List<Instant> calculateCatchUpFireTimes(JobDefinition definition, Instant firstDueTime, Instant now) {
        List<Instant> fireTimes = new ArrayList<>();
        Instant cursor = firstDueTime;
        while (!cursor.isAfter(now) && fireTimes.size() < definition.maxCatchUpCount()) {
            fireTimes.add(cursor);
            cursor = definition.schedule().nextAfter(cursor, definition.zoneId());
        }
        return fireTimes;
    }

    private Instant calculateNextFireTime(JobDefinition definition, List<Instant> fireTimes, Instant now) {
        if (fireTimes.isEmpty()) {
            return definition.schedule().nextAfter(now, definition.zoneId());
        }

        Instant cursor = fireTimes.getLast();
        Instant next = definition.schedule().nextAfter(cursor, definition.zoneId());
        if (definition.misfirePolicy() == MisfirePolicy.CATCH_UP) {
            return next;
        }
        if (!next.isAfter(now)) {
            return definition.schedule().nextAfter(now, definition.zoneId());
        }
        return next;
    }

    private String executionId(JobDefinition definition, Instant fireTime) {
        return definition.id() + "@" + fireTime;
    }
}

