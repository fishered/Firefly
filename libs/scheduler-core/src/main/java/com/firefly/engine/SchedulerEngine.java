package com.firefly.engine;

import com.firefly.domain.JobDefinition;
import com.firefly.cluster.ShardLease;
import com.firefly.cluster.ShardHasher;
import com.firefly.cluster.ShardOwnership;
import com.firefly.domain.MisfirePolicy;
import com.firefly.store.JobRepository;
import com.firefly.store.ScheduledJobRecord;
import com.firefly.metrics.SchedulerMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private long loadedConfigurationVersion = Long.MIN_VALUE;
    private Set<Integer> loadedShards = Set.of();

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
                new SchedulerMetrics(), SchedulerEngineOptions.defaults());
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
                metrics, SchedulerEngineOptions.defaults());
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
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shardOwnership = Objects.requireNonNull(shardOwnership, "shardOwnership");
        this.shardCount = shardCount;
        this.transactionalOutbox = transactionalOutbox;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.options = Objects.requireNonNull(options, "options");
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
        timer.shutdownNow();
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
        List<ScheduledJobRecord> dueRecords = timingIndex.pollDue(now, options.maxDueRecordsPerTick());
        for (ScheduledJobRecord record : dueRecords) {
            int shardId = ShardHasher.shardFor(record.definition().id(), shardCount);
            ShardLease lease = leases.get(shardId);
            if (lease == null) {
                forceReload();
                continue;
            }
            List<Instant> fireTimes = calculateFireTimes(record, now);
            Instant nextFireTime = calculateNextFireTime(record.definition(), fireTimes, now);
            Instant dispatchTime = clock.instant();
            List<ExecutionCommand> commands = fireTimes.stream().map(fireTime -> new ExecutionCommand(
                    executionId(record.definition(), fireTime), record.definition(), fireTime, dispatchTime,
                    lease.ownerNodeId(), lease.fencingToken()
            )).toList();
            boolean updated = transactionalOutbox && !commands.isEmpty()
                    ? repository.advanceAndEnqueue(
                            record.definition().id(), record.nextFireTime(), nextFireTime, commands
                    )
                    : repository.updateNextFireTimeWithLease(
                            record.definition().id(), record.nextFireTime(), nextFireTime,
                            lease.ownerNodeId(), lease.fencingToken()
                    );
            if (!updated) {
                forceReload();
                continue;
            }
            timingIndex.add(new ScheduledJobRecord(record.definition(), nextFireTime));
            commands.forEach(command -> metrics.observeScheduleDelay(
                    Duration.between(command.scheduledFireTime(), command.dispatchTime())
            ));
            if (!transactionalOutbox) commands.forEach(dispatcher::dispatch);
        }
        Instant remainingDue = timingIndex.nextFireTime();
        if (dueRecords.size() == options.maxDueRecordsPerTick()
                && remainingDue != null && !remainingDue.isAfter(now)) {
            log.warning("scheduler due backlog reached per-tick limit=" + options.maxDueRecordsPerTick());
            metrics.recordDueBacklog();
        }
    }

    private void refreshTimingIndex(Set<Integer> shardIds) {
        Set<Integer> currentShards = Set.copyOf(shardIds);
        long configurationVersion = repository.configurationVersion();
        if (!currentShards.equals(loadedShards) || configurationVersion != loadedConfigurationVersion) {
            timingIndex.replace(repository.listForShards(currentShards, shardCount));
            loadedShards = currentShards;
            loadedConfigurationVersion = configurationVersion;
        }
    }

    private void forceReload() {
        loadedConfigurationVersion = Long.MIN_VALUE;
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

