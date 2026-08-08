package com.firefly.server;

import java.time.Duration;
import java.util.Objects;

/** Capacity and shutdown bounds for local job execution. */
public record LocalWorkerOptions(int maxConcurrency, Duration shutdownTimeout) {
    public LocalWorkerOptions {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
    }

    public static LocalWorkerOptions defaults() {
        return new LocalWorkerOptions(256, Duration.ofSeconds(30));
    }
}
