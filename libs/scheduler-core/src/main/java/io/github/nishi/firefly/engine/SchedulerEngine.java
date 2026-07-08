package io.github.nishi.firefly.engine;

import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.domain.MisfirePolicy;
import io.github.nishi.firefly.store.JobRepository;
import io.github.nishi.firefly.store.ScheduledJobRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
     * Maximum number of due-job batches drained in one scheduler tick.
     *
     * <p>This caps scheduler work per tick while allowing bursts larger than one
     * batch to progress without waiting for the next 500ms timer cycle.
     */
    private static final int MAX_DUE_BATCHES_PER_TICK = 10;

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

        /**
         * Drain multiple due batches using the same logical tick time. Each record
         * is advanced with repository compare-and-set before dispatch, so stale
         * records from concurrent updates are ignored safely.
         */
        for (int batch = 0; batch < MAX_DUE_BATCHES_PER_TICK; batch++) {
            List<ScheduledJobRecord> dueRecords = repository.findDue(now, DUE_BATCH_SIZE);
            if (dueRecords.isEmpty()) {
                return;
            }

            for (ScheduledJobRecord record : dueRecords) {
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
                fireTimes.forEach(fireTime -> dispatcher.dispatch(record.definition(), fireTime));
            }

            if (dueRecords.size() < DUE_BATCH_SIZE) {
                return;
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
}

