package com.firefly.executor.netty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Bounded-by-time default result store; applications may replace it with JDBC or Redis. */
public final class InMemoryExecutorResultStore implements ExecutorResultStore {
    private final ConcurrentMap<String, Entry> results = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Clock clock;

    public InMemoryExecutorResultStore() {
        this(Duration.ofHours(1), Clock.systemUTC());
    }

    public InMemoryExecutorResultStore(Duration retention, Clock clock) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    @Override
    public Optional<ExecutorExecutionResult> find(String executionId) {
        Entry entry = results.get(executionId);
        if (entry == null) return Optional.empty();
        if (!entry.expiresAt().isAfter(clock.instant())) {
            results.remove(executionId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    @Override
    public void save(String executionId, ExecutorExecutionResult result) {
        results.put(executionId, new Entry(result, clock.instant().plus(retention)));
    }

    private record Entry(ExecutorExecutionResult result, Instant expiresAt) {
    }
}
