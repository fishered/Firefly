package com.firefly.engine;

import java.time.Duration;
import java.util.Objects;

/** Runtime limits for one scheduler timing loop. */
public record SchedulerEngineOptions(int maxDueRecordsPerTick, Duration maxIdleWakeup, int schedulingBatchSize) {
    public SchedulerEngineOptions(int maxDueRecordsPerTick, Duration maxIdleWakeup) {
        this(maxDueRecordsPerTick, maxIdleWakeup, 200);
    }

    public SchedulerEngineOptions {
        if (maxDueRecordsPerTick < 1) {
            throw new IllegalArgumentException("maxDueRecordsPerTick must be positive");
        }
        Objects.requireNonNull(maxIdleWakeup, "maxIdleWakeup");
        if (maxIdleWakeup.isZero() || maxIdleWakeup.isNegative()) {
            throw new IllegalArgumentException("maxIdleWakeup must be positive");
        }
        if (schedulingBatchSize < 1) {
            throw new IllegalArgumentException("schedulingBatchSize must be positive");
        }
    }

    public static SchedulerEngineOptions defaults() {
        return new SchedulerEngineOptions(10_000, Duration.ofMillis(500), 200);
    }
}
