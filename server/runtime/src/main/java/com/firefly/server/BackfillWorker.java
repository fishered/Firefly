package com.firefly.server;

import com.firefly.metrics.SchedulerMetrics;
import com.firefly.trigger.BackfillCoordinator;
import com.firefly.trigger.BackfillOperation;
import com.firefly.trigger.BackfillProgress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Advances durable backfills independently of the optional Admin HTTP plugin lifecycle. */
public final class BackfillWorker implements AutoCloseable {
    private static final Logger log = Logger.getLogger(BackfillWorker.class.getName());
    private final BackfillCoordinator coordinator;
    private final SchedulerMetrics metrics;
    private final Clock clock;
    private final Duration interval;
    private final int maxOperations;
    private final int maxExecutionsPerOperation;
    private ScheduledExecutorService executor;

    public BackfillWorker(
            BackfillCoordinator coordinator,
            SchedulerMetrics metrics,
            Clock clock,
            Duration interval,
            int maxOperations,
            int maxExecutionsPerOperation
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.maxOperations = maxOperations;
        this.maxExecutionsPerOperation = maxExecutionsPerOperation;
        if (interval.isZero() || interval.isNegative()
                || maxOperations < 1 || maxExecutionsPerOperation < 1) {
            throw new IllegalArgumentException("backfill worker interval and limits must be positive");
        }
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "firefly-backfill-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        try {
            List<BackfillProgress> processed = coordinator.runAvailable(
                    maxOperations, maxExecutionsPerOperation
            );
            metrics.recordBackfillOperationsProcessed(processed.size());
            updateStateMetrics(coordinator.list(1000), clock.instant());
        } catch (RuntimeException failure) {
            metrics.recordBackfillWorkerFailure();
            log.log(Level.WARNING, "backfill worker tick failed", failure);
        }
    }

    private void updateStateMetrics(List<BackfillOperation> operations, Instant now) {
        int active = 0;
        int paused = 0;
        int failedItems = 0;
        long oldestActiveAge = 0;
        for (BackfillOperation operation : operations) {
            failedItems += operation.failed();
            if (operation.status() == BackfillProgress.BackfillStatus.PAUSED) paused++;
            if (!operation.status().terminal() && operation.status() != BackfillProgress.BackfillStatus.PAUSED) {
                active++;
                oldestActiveAge = Math.max(
                        oldestActiveAge,
                        Math.max(0, Duration.between(operation.updatedAt(), now).toSeconds())
                );
            }
        }
        metrics.backfillState(active, paused, failedItems, oldestActiveAge);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
