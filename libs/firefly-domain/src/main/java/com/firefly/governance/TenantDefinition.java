package com.firefly.governance;

import java.time.Instant;
import java.util.Objects;

/** Versioned tenant policy shared by every gateway and scheduler node. */
public record TenantDefinition(
        String tenantId,
        boolean enabled,
        int weight,
        int maxConcurrent,
        long maxCpuMillis,
        long maxMemoryBytes,
        long maxBacklog,
        long maxTriggerRatePerSecond,
        long version,
        Instant updatedAt
) {
    public static final String DEFAULT_TENANT_ID = "default";

    public TenantDefinition {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (tenantId.length() > 128) throw new IllegalArgumentException("tenantId must not exceed 128 characters");
        if (weight < 1) throw new IllegalArgumentException("weight must be positive");
        if (maxConcurrent < 0 || maxCpuMillis < 0 || maxMemoryBytes < 0
                || maxBacklog < 0 || maxTriggerRatePerSecond < 0) {
            throw new IllegalArgumentException("tenant quota values must not be negative");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    /** Compatibility policy for jobs created before tenants became first class. Zero means unlimited. */
    public static TenantDefinition defaultTenant(Instant now) {
        return new TenantDefinition(DEFAULT_TENANT_ID, true, 1, 0, 0, 0, 0, 0, 1, now);
    }

    public TenantDefinition withVersion(long nextVersion, Instant now) {
        return new TenantDefinition(tenantId, enabled, weight, maxConcurrent, maxCpuMillis,
                maxMemoryBytes, maxBacklog, maxTriggerRatePerSecond, nextVersion, now);
    }
}
