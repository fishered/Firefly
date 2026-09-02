package com.firefly.trigger;

import java.time.Duration;
import java.util.Set;

/** Runtime guardrails for resumable historical execution. */
public record BackfillOptions(int batchSize, int rateLimitPerSecond, int canaryPercent, Set<java.time.Instant> retryOnlyTimes) {
    public BackfillOptions {
        if (batchSize < 1 || batchSize > 10_000) throw new IllegalArgumentException("batchSize out of bounds");
        if (rateLimitPerSecond < 0 || rateLimitPerSecond > 1_000_000) throw new IllegalArgumentException("rateLimitPerSecond out of bounds");
        if (canaryPercent < 0 || canaryPercent > 100) throw new IllegalArgumentException("canaryPercent out of bounds");
        retryOnlyTimes = Set.copyOf(retryOnlyTimes == null ? Set.of() : retryOnlyTimes);
    }

    public static BackfillOptions defaults() {
        return new BackfillOptions(100, 0, 100, Set.of());
    }

    public Duration minimumInterval() {
        return rateLimitPerSecond == 0 ? Duration.ZERO : Duration.ofNanos(1_000_000_000L / rateLimitPerSecond);
    }
}
