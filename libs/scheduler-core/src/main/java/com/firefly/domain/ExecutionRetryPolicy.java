package com.firefly.domain;

import java.time.Duration;
import java.util.Objects;

/** Whole-run retry policy. maxAttempts includes the initial execution. */
public record ExecutionRetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        boolean retryOnFailure,
        boolean retryOnTimeout
) {
    public ExecutionRetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("retry delays must not be negative");
        }
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be at least 1");
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
        }
    }

    public static ExecutionRetryPolicy none() {
        return new ExecutionRetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, false, false);
    }

    public Duration delayBeforeAttempt(int attempt) {
        if (attempt < 1) return Duration.ZERO;
        double factor = Math.pow(multiplier, attempt - 1.0);
        long millis = Math.min(maxDelay.toMillis(), Math.round(initialDelay.toMillis() * factor));
        return Duration.ofMillis(Math.max(0, millis));
    }
}
