package com.firefly.governance;

import java.time.Instant;
import java.util.Objects;

/** Authoritative resource usage held in the shared admission store. */
public record TenantUsage(
        String tenantId,
        int activeExecutions,
        long reservedCpuMillis,
        long reservedMemoryBytes,
        long backlog,
        Instant triggerWindowStartedAt,
        long triggersInWindow,
        long version
) {
    public TenantUsage {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(triggerWindowStartedAt, "triggerWindowStartedAt");
        if (activeExecutions < 0 || reservedCpuMillis < 0 || reservedMemoryBytes < 0
                || backlog < 0 || triggersInWindow < 0 || version < 0) {
            throw new IllegalArgumentException("tenant usage values must not be negative");
        }
    }

    public static TenantUsage empty(String tenantId, Instant now) {
        return new TenantUsage(tenantId, 0, 0, 0, 0, now, 0, 0);
    }
}
