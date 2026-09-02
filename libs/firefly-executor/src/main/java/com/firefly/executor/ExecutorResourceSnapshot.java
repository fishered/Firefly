package com.firefly.executor;

import java.util.Set;

/** Last reported capacity and admission state of one executor instance. */
public record ExecutorResourceSnapshot(String executorName, String instanceId,
                                       long availableCpuMillis, long availableMemoryBytes,
                                       Set<String> tags, int activeExecutions,
                                       int tenantActiveExecutions) {
    public ExecutorResourceSnapshot {
        if (executorName == null || executorName.isBlank() || instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("executor identity is required");
        }
        if (availableCpuMillis < 0 || availableMemoryBytes < 0 || activeExecutions < 0 || tenantActiveExecutions < 0) {
            throw new IllegalArgumentException("resource values must not be negative");
        }
        tags = Set.copyOf(tags == null ? Set.of() : tags);
    }
}
