package com.firefly.store;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Dynamic weights and aging cadence used while selecting a dispatch claim batch. */
public record DispatchFairnessPolicy(Map<String, Integer> tenantWeights, Duration agingInterval) {
    public DispatchFairnessPolicy {
        tenantWeights = Map.copyOf(tenantWeights == null ? Map.of() : tenantWeights);
        Objects.requireNonNull(agingInterval, "agingInterval");
        if (agingInterval.isZero() || agingInterval.isNegative()) {
            throw new IllegalArgumentException("agingInterval must be positive");
        }
        if (tenantWeights.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 1)) {
            throw new IllegalArgumentException("tenant weights must use non-blank ids and positive values");
        }
    }

    public static DispatchFairnessPolicy defaults() {
        return new DispatchFairnessPolicy(Map.of(), Duration.ofSeconds(30));
    }

    public int weightOf(String tenantId) {
        return tenantWeights.getOrDefault(tenantId, 1);
    }
}
