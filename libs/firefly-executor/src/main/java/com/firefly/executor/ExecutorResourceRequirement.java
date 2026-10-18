package com.firefly.executor;

import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** Parses the backward-compatible job parameter contract used by 1.1.x dispatch requests. */
    public static ExecutorResourceRequirement fromParameters(Map<String, String> parameters) {
        Map<String, String> values = parameters == null ? Map.of() : parameters;
        String tenant = values.getOrDefault("firefly.resource.tenantId", "default").trim();
        Set<String> tags = java.util.Arrays.stream(
                        values.getOrDefault("firefly.resource.requiredTags", "").split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new ExecutorResourceRequirement(
                tenant.isEmpty() ? "default" : tenant,
                number(values, "firefly.resource.cpuMillis"),
                number(values, "firefly.resource.memoryBytes"),
                tags,
                Math.toIntExact(number(values, "firefly.resource.priority")),
                Math.toIntExact(number(values, "firefly.resource.maxTenantConcurrent"))
        );
    }

    private static long number(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be a non-negative integer", invalid);
        }
    }
}
