package com.firefly.idempotency;

import java.time.Instant;
import java.util.Objects;

/** Business idempotency store that fences stale owners after an abandoned claim is recovered. */
public interface FencedBusinessIdempotencyStore extends BusinessIdempotencyStore {
    Claim tryAcquireFenced(String key, Instant acquiredAt);

    boolean markCompletedFenced(String key, String claimToken, Instant completedAt);

    boolean releaseFenced(String key, String claimToken, Instant releasedAt, String errorMessage);

    @Override
    default AcquireResult tryAcquire(String key, Instant acquiredAt) {
        return tryAcquireFenced(key, acquiredAt).result();
    }

    @Override
    default void markCompleted(String key, Instant completedAt) {
        throw new UnsupportedOperationException("claim token is required");
    }

    @Override
    default void release(String key, Instant releasedAt, String errorMessage) {
        throw new UnsupportedOperationException("claim token is required");
    }

    record Claim(AcquireResult result, String claimToken) {
        public Claim {
            Objects.requireNonNull(result, "result");
            claimToken = claimToken == null ? "" : claimToken;
            if (result == AcquireResult.ACQUIRED && claimToken.isBlank()) {
                throw new IllegalArgumentException("an acquired claim requires a token");
            }
        }

        public static Claim acquired(String token) {
            return new Claim(AcquireResult.ACQUIRED, token);
        }

        public static Claim inProgress() {
            return new Claim(AcquireResult.IN_PROGRESS, "");
        }

        public static Claim completed() {
            return new Claim(AcquireResult.COMPLETED, "");
        }
    }
}
