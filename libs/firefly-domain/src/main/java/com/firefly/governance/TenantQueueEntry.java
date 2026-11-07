package com.firefly.governance;

import java.time.Instant;
import java.util.Objects;

/** Immutable queue metadata used by the transport-neutral fair scheduler. */
public record TenantQueueEntry<T>(
        String entryId,
        String tenantId,
        int priority,
        Instant enqueuedAt,
        T value
) {
    public TenantQueueEntry {
        Objects.requireNonNull(entryId, "entryId");
        tenantId = tenantId == null || tenantId.isBlank() ? TenantDefinition.DEFAULT_TENANT_ID : tenantId;
        Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        Objects.requireNonNull(value, "value");
        if (entryId.isBlank()) throw new IllegalArgumentException("entryId must not be blank");
        if (priority < TenantPriority.MIN_LEVEL || priority > TenantPriority.MAX_LEVEL) {
            throw new IllegalArgumentException("priority must be between 0 and 9");
        }
    }

    public static <T> TenantQueueEntry<T> of(String entryId, String tenantId, TenantPriority priority,
                                              Instant enqueuedAt, T value) {
        return new TenantQueueEntry<>(entryId, tenantId, priority.level(), enqueuedAt, value);
    }
}
