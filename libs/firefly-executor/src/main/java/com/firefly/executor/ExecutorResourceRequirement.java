package com.firefly.executor;

import java.util.Set;

/** Resource and tenant admission requirements for one dispatch. */
public record ExecutorResourceRequirement(String tenantId, long cpuMillis, long memoryBytes,
                                          Set<String> requiredTags, int priority,
                                          int maxTenantConcurrent) {
    public ExecutorResourceRequirement {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (cpuMillis < 0 || memoryBytes < 0 || priority < 0) throw new IllegalArgumentException("resource values must not be negative");
        if (maxTenantConcurrent < 0) throw new IllegalArgumentException("maxTenantConcurrent must not be negative");
        requiredTags = Set.copyOf(requiredTags == null ? Set.of() : requiredTags);
    }

    public static ExecutorResourceRequirement defaults(String tenantId) {
        return new ExecutorResourceRequirement(tenantId, 0, 0, Set.of(), 0, 0);
    }
}
