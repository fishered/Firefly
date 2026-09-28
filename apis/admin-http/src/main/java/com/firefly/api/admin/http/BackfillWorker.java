package com.firefly.api.admin.http;

import com.firefly.trigger.BackfillCoordinator;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small API-node worker that advances durable backfill operations under a store lease. */
final class BackfillWorker implements AutoCloseable {
    private static final Logger log = Logger.getLogger(BackfillWorker.class.getName());
    private final BackfillCoordinator coordinator;
    private final Duration interval;
    private ScheduledExecutorService executor;

    BackfillWorker(BackfillCoordinator coordinator, Duration interval) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "firefly-backfill-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            coordinator.runAvailable(8, 100);
        } catch (RuntimeException failure) {
            log.log(Level.WARNING, "backfill worker tick failed", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
