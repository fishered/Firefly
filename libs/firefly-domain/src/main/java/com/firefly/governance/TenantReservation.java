package com.firefly.governance;

import java.time.Instant;
import java.util.Objects;

/**
 * Idempotent leased reservation. {@code createdAt} is refreshed on an idempotent renewal and
 * therefore acts as the current lease epoch used by orphan reconciliation. Trigger-rate units
 * are intentionally not subtracted when the reservation is released.
 */
public record TenantReservation(
        String reservationId,
        String tenantId,
        int concurrentExecutions,
        long cpuMillis,
        long memoryBytes,
        long backlog,
        long triggers,
        Instant createdAt,
        Instant expiresAt
) {
    public TenantReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (concurrentExecutions < 0 || cpuMillis < 0 || memoryBytes < 0 || backlog < 0 || triggers < 0) {
            throw new IllegalArgumentException("reserved resource values must not be negative");
        }
        if (expiresAt.isBefore(createdAt)) throw new IllegalArgumentException("expiresAt must not precede createdAt");
    }
}
