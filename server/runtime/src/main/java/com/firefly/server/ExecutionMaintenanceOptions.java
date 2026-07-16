package com.firefly.server;

import java.time.Duration;
import java.util.Objects;

/** Runtime tuning for execution timeout and history maintenance. */
public record ExecutionMaintenanceOptions(
        Duration initialDelay,
        Duration interval,
        Duration retention,
        int timeoutBatchSize,
        int cleanupBatchSize
) {
    public ExecutionMaintenanceOptions {
        requireNonNegative(initialDelay, "initialDelay");
        requirePositive(interval, "interval");
        requirePositive(retention, "retention");
        if (timeoutBatchSize < 1) throw new IllegalArgumentException("timeoutBatchSize must be positive");
        if (cleanupBatchSize < 1) throw new IllegalArgumentException("cleanupBatchSize must be positive");
    }

    public static ExecutionMaintenanceOptions defaults() {
        return new ExecutionMaintenanceOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofDays(30), 200, 500
        );
    }

    private static void requirePositive(Duration value, String name) {
        requireNonNegative(value, name);
        if (value.isZero()) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
    }
}
