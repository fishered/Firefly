package com.firefly.domain;

import lombok.Builder;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * Defines the stable scheduling contract for a job without carrying runtime execution state.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record JobDefinition(
        String id,
        String groupId,
        String name,
        String handlerName,
        Schedule schedule,
        ZoneId zoneId,
        MisfirePolicy misfirePolicy,
        Duration misfireGrace,
        ConcurrencyPolicy concurrencyPolicy,
        int maxCatchUpCount,
        Duration timeout,
        Map<String, String> parameters,
        boolean enabled
) {
    /**
     * Keeps builder-created and manually-created definitions under the same validation rules.
     */
    public JobDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handlerName, "handlerName");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(misfirePolicy, "misfirePolicy");
        Objects.requireNonNull(misfireGrace, "misfireGrace");
        Objects.requireNonNull(concurrencyPolicy, "concurrencyPolicy");
        Objects.requireNonNull(timeout, "timeout");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (handlerName.isBlank()) {
            throw new IllegalArgumentException("handlerName must not be blank");
        }
        if (maxCatchUpCount < 1) {
            throw new IllegalArgumentException("maxCatchUpCount must be greater than 0");
        }
        if (misfireGrace.isNegative()) {
            throw new IllegalArgumentException("misfireGrace must not be negative");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /**
     * Pre-seeds product defaults while delegating the fluent API and build method to Lombok.
     */
    public static Builder builder() {
        return newBuilder()
                .groupId("default")
                .zoneId(ZoneId.of("UTC"))
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ofSeconds(5))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .maxCatchUpCount(10)
                .timeout(Duration.ofMinutes(5))
                .parameters(Map.of())
                .enabled(true);
    }
}

