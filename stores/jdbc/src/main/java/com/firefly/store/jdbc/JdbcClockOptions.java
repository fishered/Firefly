package com.firefly.store.jdbc;

import java.time.Duration;
import java.util.Objects;

/** Controls periodic calibration of JVM time against the shared database. */
public record JdbcClockOptions(Duration syncInterval, Duration driftWarningThreshold) {
    public JdbcClockOptions {
        requirePositive(syncInterval, "syncInterval");
        requirePositive(driftWarningThreshold, "driftWarningThreshold");
    }

    public static JdbcClockOptions defaults() {
        return new JdbcClockOptions(Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
