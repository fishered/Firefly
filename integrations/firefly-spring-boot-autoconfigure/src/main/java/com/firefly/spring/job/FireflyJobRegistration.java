package com.firefly.spring.job;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRetryScope;
import com.firefly.domain.ExecutorRoutingStrategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declares a remote scheduled job that the Spring Boot starter can create in Firefly.
 */
public record FireflyJobRegistration(
        String id,
        String name,
        String groupId,
        String handlerName,
        String cron,
        String zoneId,
        boolean enabled,
        ExecutorDispatchMode dispatchMode,
        ExecutorRoutingStrategy routingStrategy,
        ExecutorCompletionPolicy completionPolicy,
        int shardCount,
        String routingKey,
        ExecutorRetryScope retryScope,
        int retryMaxAttempts,
        Map<String, String> parameters
) {
    public FireflyJobRegistration {
        id = requireNonBlank(id, "id");
        name = defaultIfBlank(name, id);
        groupId = defaultIfBlank(groupId, "default");
        handlerName = requireNonBlank(handlerName, "handlerName");
        cron = requireNonBlank(cron, "cron");
        zoneId = defaultIfBlank(zoneId, "UTC");
        Objects.requireNonNull(dispatchMode, "dispatchMode");
        Objects.requireNonNull(routingStrategy, "routingStrategy");
        Objects.requireNonNull(completionPolicy, "completionPolicy");
        Objects.requireNonNull(retryScope, "retryScope");
        routingKey = routingKey == null ? "" : routingKey;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("retryMaxAttempts must be positive");
        }
    }

    public static FireflyJobRegistration of(String id, String handlerName, String cron) {
        return builder(id, handlerName, cron).build();
    }

    public static Builder builder(String id, String handlerName, String cron) {
        return new Builder(id, handlerName, cron);
    }

    public static final class Builder {
        private final String id;
        private final String handlerName;
        private final String cron;
        private String name;
        private String groupId = "default";
        private String zoneId = "UTC";
        private boolean enabled = true;
        private ExecutorDispatchMode dispatchMode = ExecutorDispatchMode.UNICAST;
        private ExecutorRoutingStrategy routingStrategy = ExecutorRoutingStrategy.ROUND_ROBIN;
        private ExecutorCompletionPolicy completionPolicy = ExecutorCompletionPolicy.ALL_SUCCESS;
        private int shardCount = 1;
        private String routingKey = "";
        private ExecutorRetryScope retryScope = ExecutorRetryScope.FAILED_TARGETS_ONLY;
        private int retryMaxAttempts = 1;
        private final Map<String, String> parameters = new LinkedHashMap<>();

        private Builder(String id, String handlerName, String cron) {
            this.id = id;
            this.handlerName = handlerName;
            this.cron = cron;
            this.name = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder zoneId(String zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder dispatchMode(ExecutorDispatchMode dispatchMode) {
            this.dispatchMode = dispatchMode;
            return this;
        }

        public Builder routingStrategy(ExecutorRoutingStrategy routingStrategy) {
            this.routingStrategy = routingStrategy;
            return this;
        }

        public Builder completionPolicy(ExecutorCompletionPolicy completionPolicy) {
            this.completionPolicy = completionPolicy;
            return this;
        }

        public Builder shardCount(int shardCount) {
            this.shardCount = shardCount;
            return this;
        }

        public Builder routingKey(String routingKey) {
            this.routingKey = routingKey;
            return this;
        }

        public Builder retryScope(ExecutorRetryScope retryScope) {
            this.retryScope = retryScope;
            return this;
        }

        public Builder retryMaxAttempts(int retryMaxAttempts) {
            this.retryMaxAttempts = retryMaxAttempts;
            return this;
        }

        public Builder parameter(String name, String value) {
            parameters.put(requireNonBlank(name, "parameter name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public FireflyJobRegistration build() {
            return new FireflyJobRegistration(
                    id, name, groupId, handlerName, cron, zoneId, enabled,
                    dispatchMode, routingStrategy, completionPolicy, shardCount,
                    routingKey, retryScope, retryMaxAttempts, parameters
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
