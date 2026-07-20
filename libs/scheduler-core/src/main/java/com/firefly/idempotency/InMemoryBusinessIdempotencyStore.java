package com.firefly.idempotency;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Local-only implementation for tests and standalone demos. */
public final class InMemoryBusinessIdempotencyStore implements BusinessIdempotencyStore {
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    @Override
    public AcquireResult tryAcquire(String key, Instant acquiredAt) {
        State created = new State(false, acquiredAt);
        State existing = states.putIfAbsent(key, created);
        if (existing == null) return AcquireResult.ACQUIRED;
        return existing.completed ? AcquireResult.COMPLETED : AcquireResult.IN_PROGRESS;
    }

    @Override
    public void markCompleted(String key, Instant completedAt) {
        states.put(key, new State(true, completedAt));
    }

    @Override
    public void release(String key, Instant releasedAt, String errorMessage) {
        states.computeIfPresent(key, (ignored, state) -> state.completed ? state : null);
    }

    private record State(boolean completed, Instant updatedAt) {
    }
}
