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
        JobDestination destination,
        ExecutionRetryPolicy retryPolicy,
        ExecutorDispatchMode dispatchMode,
        ExecutorRoutingStrategy routingStrategy,
        ExecutorCompletionPolicy completionPolicy,
        int shardCount,
        String routingKey,
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
        destination = destination == null ? legacyDestination(handlerName, parameters) : destination;
        retryPolicy = retryPolicy == null ? legacyRetryPolicy(parameters) : retryPolicy;
        Objects.requireNonNull(dispatchMode, "dispatchMode");
        Objects.requireNonNull(routingStrategy, "routingStrategy");
        Objects.requireNonNull(completionPolicy, "completionPolicy");
        Objects.requireNonNull(routingKey, "routingKey");
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
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be greater than 0");
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
                .destination(null)
                .retryPolicy(null)
                .dispatchMode(ExecutorDispatchMode.UNICAST)
                .routingStrategy(ExecutorRoutingStrategy.ROUND_ROBIN)
                .completionPolicy(ExecutorCompletionPolicy.ALL_SUCCESS)
                .shardCount(1)
                .routingKey("")
                .enabled(true);
    }

    public JobDefinition withEnabled(boolean value) {
        return new JobDefinition(
                id, groupId, name, handlerName, schedule, zoneId, misfirePolicy, misfireGrace,
                concurrencyPolicy, maxCatchUpCount, timeout, parameters, destination, retryPolicy,
                dispatchMode, routingStrategy,
                completionPolicy, shardCount, routingKey, value
        );
    }

    public boolean remote() {
        return destination.remote();
    }

    public String businessHandlerName() {
        String configured = parameters.get("handlerName");
        if (configured != null && !configured.isBlank()) return configured;
        if (handlerName.startsWith("remote:")) {
            String[] parts = handlerName.split(":", 3);
            if (parts.length == 3) return parts[2];
        }
        return handlerName;
    }

    private static JobDestination legacyDestination(String handlerName, Map<String, String> parameters) {
        String executorName = parameters.get("executorName");
        if (executorName != null && !executorName.isBlank()) return JobDestination.remote(executorName);
        if (handlerName.startsWith("remote:")) {
            String[] parts = handlerName.split(":", 3);
            if (parts.length > 1 && !parts[1].isBlank()) return JobDestination.remote(parts[1]);
        }
        return JobDestination.local();
    }

    private static ExecutionRetryPolicy legacyRetryPolicy(Map<String, String> parameters) {
        String maxAttempts = parameters.get("firefly.retry.maxAttempts");
        if (maxAttempts == null) return ExecutionRetryPolicy.none();
        return new ExecutionRetryPolicy(
                Integer.parseInt(maxAttempts),
                Duration.parse(parameters.getOrDefault("firefly.retry.initialDelay", "PT0S")),
                Double.parseDouble(parameters.getOrDefault("firefly.retry.multiplier", "1.0")),
                Duration.parse(parameters.getOrDefault("firefly.retry.maxDelay", "PT0S")),
                Boolean.parseBoolean(parameters.getOrDefault("firefly.retry.onFailure", "true")),
                Boolean.parseBoolean(parameters.getOrDefault("firefly.retry.onTimeout", "true"))
        );
    }

}

