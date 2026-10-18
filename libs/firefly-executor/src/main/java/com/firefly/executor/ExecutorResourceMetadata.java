package com.firefly.executor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Stable metadata encoding shared by executor heartbeats, directories, Admin API, and routing. */
public final class ExecutorResourceMetadata {
    public static final String AVAILABLE_CPU_MILLIS = "firefly.resource.availableCpuMillis";
    public static final String AVAILABLE_MEMORY_BYTES = "firefly.resource.availableMemoryBytes";
    public static final String TAGS = "firefly.resource.tags";
    public static final String ACTIVE_EXECUTIONS = "firefly.resource.activeExecutions";
    public static final String TENANT_ACTIVE_EXECUTIONS = "firefly.resource.tenantActiveExecutions";

    private ExecutorResourceMetadata() {
    }

    public static Map<String, String> merge(Map<String, String> base, ExecutorResourceReport report) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(base == null ? Map.of() : base);
        result.put(AVAILABLE_CPU_MILLIS, Long.toString(report.availableCpuMillis()));
        result.put(AVAILABLE_MEMORY_BYTES, Long.toString(report.availableMemoryBytes()));
        result.put(TAGS, report.tags().stream().sorted().collect(Collectors.joining(",")));
        result.put(ACTIVE_EXECUTIONS, Integer.toString(report.activeExecutions()));
        result.put(TENANT_ACTIVE_EXECUTIONS, encodeTenantCounts(report.tenantActiveExecutions()));
        return Map.copyOf(result);
    }

    public static Map<String, String> copyReport(Map<String, String> source, Map<String, String> base) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(base == null ? Map.of() : base);
        for (String key : keys()) {
            String value = source == null ? null : source.get(key);
            if (value != null) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    public static Optional<ExecutorResourceReport> report(Map<String, String> metadata) {
        if (metadata == null || !metadata.containsKey(AVAILABLE_CPU_MILLIS)
                || !metadata.containsKey(AVAILABLE_MEMORY_BYTES)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ExecutorResourceReport(
                    Long.parseLong(metadata.get(AVAILABLE_CPU_MILLIS)),
                    Long.parseLong(metadata.get(AVAILABLE_MEMORY_BYTES)),
                    tags(metadata.get(TAGS)),
                    Integer.parseInt(metadata.getOrDefault(ACTIVE_EXECUTIONS, "0")),
                    decodeTenantCounts(metadata.get(TENANT_ACTIVE_EXECUTIONS))
            ));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static Optional<ExecutorResourceSnapshot> snapshot(
            String executorName, String instanceId, Map<String, String> metadata, String tenantId
    ) {
        return report(metadata).map(value -> new ExecutorResourceSnapshot(
                executorName, instanceId, value.availableCpuMillis(), value.availableMemoryBytes(),
                value.tags(), value.activeExecutions(), value.tenantActiveExecutions().getOrDefault(tenantId, 0)
        ));
    }

    /**
     * Applies a reservation to the latest report. Callers must serialize this with the directory row update.
     */
    public static Optional<Map<String, String>> reserve(
            Map<String, String> metadata, String executorName, String instanceId,
            ExecutorResourceRequirement requirement
    ) {
        Optional<ExecutorResourceReport> current = report(metadata);
        if (current.isEmpty()) return Optional.empty();
        ExecutorResourceReport report = current.get();
        int tenantActive = report.tenantActiveExecutions().getOrDefault(requirement.tenantId(), 0);
        ExecutorResourceSnapshot snapshot = new ExecutorResourceSnapshot(
                executorName, instanceId, report.availableCpuMillis(), report.availableMemoryBytes(),
                report.tags(), report.activeExecutions(), tenantActive
        );
        if (snapshot.availableCpuMillis() < requirement.cpuMillis()
                || snapshot.availableMemoryBytes() < requirement.memoryBytes()
                || !snapshot.tags().containsAll(requirement.requiredTags())
                || requirement.maxTenantConcurrent() > 0
                && snapshot.tenantActiveExecutions() >= requirement.maxTenantConcurrent()) {
            return Optional.empty();
        }
        TreeMap<String, Integer> tenants = new TreeMap<>(report.tenantActiveExecutions());
        tenants.put(requirement.tenantId(), tenantActive + 1);
        return Optional.of(merge(metadata, new ExecutorResourceReport(
                report.availableCpuMillis() - requirement.cpuMillis(),
                report.availableMemoryBytes() - requirement.memoryBytes(),
                report.tags(), report.activeExecutions() + 1, tenants
        )));
    }

    public static boolean constrained(ExecutorResourceRequirement requirement) {
        return requirement.cpuMillis() > 0 || requirement.memoryBytes() > 0
                || !requirement.requiredTags().isEmpty() || requirement.maxTenantConcurrent() > 0;
    }

    private static List<String> keys() {
        return List.of(AVAILABLE_CPU_MILLIS, AVAILABLE_MEMORY_BYTES, TAGS,
                ACTIVE_EXECUTIONS, TENANT_ACTIVE_EXECUTIONS);
    }

    private static Set<String> tags(String encoded) {
        if (encoded == null || encoded.isBlank()) return Set.of();
        return java.util.Arrays.stream(encoded.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private static String encodeTenantCounts(Map<String, Integer> counts) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8))
                        + ":" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private static Map<String, Integer> decodeTenantCounts(String encoded) {
        if (encoded == null || encoded.isBlank()) return Map.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String item : encoded.split(",")) {
            int separator = item.lastIndexOf(':');
            if (separator <= 0 || separator == item.length() - 1) {
                throw new IllegalArgumentException("invalid tenant resource count");
            }
            String tenant = new String(decoder.decode(item.substring(0, separator)), StandardCharsets.UTF_8);
            result.put(tenant, Integer.parseInt(item.substring(separator + 1)));
        }
        return Map.copyOf(result);
    }
}
