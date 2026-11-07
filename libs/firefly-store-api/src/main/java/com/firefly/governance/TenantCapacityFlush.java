package com.firefly.governance;

import java.util.Objects;

/** Idempotent write envelope for one source and bucket. */
public record TenantCapacityFlush(String flushId, TenantCapacitySample sample) {
    public TenantCapacityFlush {
        Objects.requireNonNull(flushId, "flushId");
        Objects.requireNonNull(sample, "sample");
        if (flushId.isBlank() || flushId.length() > 512) {
            throw new IllegalArgumentException("flushId must be 1..512 characters");
        }
    }
}
