package com.firefly.idempotency;

import java.time.Instant;

/** Atomic business-key guard; production implementations should live in the business database. */
public interface BusinessIdempotencyStore {
    AcquireResult tryAcquire(String key, Instant acquiredAt);

    void markCompleted(String key, Instant completedAt);

    void release(String key, Instant releasedAt, String errorMessage);

    enum AcquireResult {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }
}
