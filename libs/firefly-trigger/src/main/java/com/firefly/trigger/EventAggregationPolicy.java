package com.firefly.trigger;

import java.time.Duration;
import java.util.Objects;

/** Bounds the amount of time an event group may wait before one execution is released. */
public record EventAggregationPolicy(String aggregationKey, Duration debounceWindow, Duration maxDelay) {
    public EventAggregationPolicy {
        if (aggregationKey == null || aggregationKey.isBlank()) {
            throw new IllegalArgumentException("aggregationKey must not be blank");
        }
        if (aggregationKey.length() > 256) throw new IllegalArgumentException("aggregationKey is too long");
        Objects.requireNonNull(debounceWindow, "debounceWindow");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (debounceWindow.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(debounceWindow) < 0) {
            throw new IllegalArgumentException("maxDelay must be at least debounceWindow and neither may be negative");
        }
    }
}
