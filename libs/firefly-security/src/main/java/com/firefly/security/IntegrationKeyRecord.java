package com.firefly.security;

import java.time.Instant;
import java.util.Objects;

/** Persistent digest and rotation metadata for the single system integration key. */
public record IntegrationKeyRecord(
        String keyHash,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public IntegrationKeyRecord {
        if (keyHash == null || keyHash.isBlank()) {
            throw new IllegalArgumentException("keyHash must not be blank");
        }
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
