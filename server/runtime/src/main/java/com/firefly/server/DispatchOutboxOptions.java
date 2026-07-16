package com.firefly.server;

import java.time.Duration;
import java.util.Objects;

/** Runtime tuning for reliable outbox delivery. */
public record DispatchOutboxOptions(
        Duration pollInterval,
        int claimBatchSize,
        Duration claimDuration,
        Duration remoteAckTimeout,
        int maxAttempts,
        Duration maxRetryBackoff
) {
    public DispatchOutboxOptions {
        requirePositive(pollInterval, "pollInterval");
        if (claimBatchSize < 1) throw new IllegalArgumentException("claimBatchSize must be positive");
        requirePositive(claimDuration, "claimDuration");
        requirePositive(remoteAckTimeout, "remoteAckTimeout");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        requirePositive(maxRetryBackoff, "maxRetryBackoff");
    }

    public static DispatchOutboxOptions defaults() {
        return new DispatchOutboxOptions(
                Duration.ofMillis(200), 50, Duration.ofSeconds(15),
                Duration.ofSeconds(10), 5, Duration.ofSeconds(30)
        );
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
