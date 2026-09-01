package com.firefly.executor;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ExecutorInstanceLocation(
        String executorName,
        String instanceId,
        String gatewayNodeId,
        String gatewayAddress,
        String sessionId,
        Instant lastSeenAt,
        Instant leaseUntil,
        Map<String, String> metadata
) {
    public ExecutorInstanceLocation {
        Objects.requireNonNull(executorName, "executorName");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(gatewayNodeId, "gatewayNodeId");
        gatewayAddress = gatewayAddress == null ? "" : gatewayAddress;
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
