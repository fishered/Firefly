package com.firefly.executor;

import java.util.Map;
import java.util.Set;

/** Live capacity reported by one executor process on registration and heartbeat. */
public record ExecutorResourceReport(
        long availableCpuMillis,
        long availableMemoryBytes,
        Set<String> tags,
        int activeExecutions,
        Map<String, Integer> tenantActiveExecutions
) {
    public ExecutorResourceReport {
        if (availableCpuMillis < 0 || availableMemoryBytes < 0 || activeExecutions < 0) {
            throw new IllegalArgumentException("resource values must not be negative");
        }
        tags = Set.copyOf(tags == null ? Set.of() : tags);
        if (tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.contains(","))) {
            throw new IllegalArgumentException("resource tags must be non-blank and must not contain commas");
        }
        tenantActiveExecutions = Map.copyOf(tenantActiveExecutions == null ? Map.of() : tenantActiveExecutions);
        if (tenantActiveExecutions.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("tenant execution counts must not be negative");
        }
    }
}
