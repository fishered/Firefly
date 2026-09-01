package com.firefly.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Coalesces intermediate progress while always admitting terminal updates. */
public final class BatchProgressLimiter {
    private final long intervalNanos;
    private final ConcurrentHashMap<String, Long> lastPublished = new ConcurrentHashMap<>();
    public BatchProgressLimiter(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
        intervalNanos = interval.toNanos();
    }
    public boolean admit(String rootExecutionId, BatchProgress progress, Instant now, boolean terminal) {
        Objects.requireNonNull(rootExecutionId, "rootExecutionId"); Objects.requireNonNull(progress, "progress"); Objects.requireNonNull(now, "now");
        if (terminal || progress.percent() >= 100) { lastPublished.put(rootExecutionId, now.toEpochMilli() * 1_000_000L); return true; }
        long current = now.toEpochMilli() * 1_000_000L;
        Long previous = lastPublished.get(rootExecutionId);
        if (previous != null && current - previous < intervalNanos) return false;
        return lastPublished.replace(rootExecutionId, previous, current) || lastPublished.putIfAbsent(rootExecutionId, current) == null;
    }
}
