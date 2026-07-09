package io.github.nishi.firefly.engine;

import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.domain.MisfirePolicy;
import io.github.nishi.firefly.store.DueJobBatch;
import io.github.nishi.firefly.store.JobRepository;
import io.github.nishi.firefly.store.ScheduledJobRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * Maximum records loaded from the repository in a single due-job query.
     */
    private static final int DUE_BATCH_SIZE = 100;

    /**
     * Hard guardrail for jobs sharing one exact fire time.
     */
    private static final int SAME_FIRE_TIME_HARD_LIMIT = 10_000;

    /**
     * Maximum due records advanced in one scheduler tick.
     *
     * <p>This protects the timer thread from unbounded backlog while allowing
     * many different fire-time groups to progress in the same tick.
     */
    private static final int MAX_DUE_RECORDS_PER_TICK = 10_000;

    /**
     * Secondary guardrail for pathological cases with many tiny fire-time groups.
     */
    private static final int MAX_DUE_FIRE_TIME_GROUPS_PER_TICK = 10_000;

    private final JobRepository repository;
    private final JobDispatcher dispatcher;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean started = new AtomicBoolean();

    public SchedulerEngine(JobRepository repository, JobDispatcher dispatcher, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        timer.scheduleWithFixedDelay(this::safeTick, 0, 500, TimeUnit.MILLISECONDS);
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

    public void tick() {
        Instant now = clock.instant();
        Set<String> processedJobIds = new HashSet<>();

        /**
         * Drain same-fire-time batch dispatch groups using the same logical tick
         * time. Each record is advanced with repository compare-and-set before
         * dispatch, so stale records from concurrent updates are ignored safely.
         */
        int processedRecords = 0;
        for (int group = 0; group < MAX_DUE_FIRE_TIME_GROUPS_PER_TICK
                && processedRecords < MAX_DUE_RECORDS_PER_TICK; group++) {
            int remainingRecordBudget = MAX_DUE_RECORDS_PER_TICK - processedRecords;
            DueJobBatch dueBatch = repository.findDueBatch(
                    now,
                    DUE_BATCH_SIZE,
                    Math.min(SAME_FIRE_TIME_HARD_LIMIT, remainingRecordBudget),
                    processedJobIds
            );
            List<ScheduledJobRecord> dueRecords = dueBatch.records();
            if (dueRecords.isEmpty()) {
                return;
            }
            processedRecords += dueRecords.size();
            if (dueBatch.truncated()) {
                log.warning(() -> "same fire-time batch reached hard limit, fireTime="
                        + dueBatch.fireTime()
                        + ", limit=" + SAME_FIRE_TIME_HARD_LIMIT);
            }

            for (ScheduledJobRecord record : dueRecords) {
                processedJobIds.add(record.definition().id());
                List<Instant> fireTimes = calculateFireTimes(record, now);
                Instant nextFireTime = calculateNextFireTime(record.definition(), fireTimes, now);
                boolean updated = repository.updateNextFireTime(
                        record.definition().id(),
                        record.nextFireTime(),
                        nextFireTime
                );
                if (!updated) {
                    continue;
                }
                Instant dispatchTime = clock.instant();
                fireTimes.forEach(fireTime -> dispatcher.dispatch(new ExecutionCommand(
                        executionId(record.definition(), fireTime),
                        record.definition(),
                        fireTime,
                        dispatchTime
                )));
            }
        }
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

