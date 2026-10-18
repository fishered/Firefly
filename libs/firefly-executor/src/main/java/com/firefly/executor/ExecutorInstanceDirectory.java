package com.firefly.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ExecutorInstanceDirectory {
    void register(ExecutorInstanceLocation location);

    boolean heartbeat(
            String executorName, String instanceId, String gatewayNodeId,
            String sessionId, Instant now, Duration leaseDuration
    );

    default boolean heartbeat(
            String executorName, String instanceId, String gatewayNodeId,
            String sessionId, Instant now, Duration leaseDuration,
            Map<String, String> metadata
    ) {
        return heartbeat(executorName, instanceId, gatewayNodeId, sessionId, now, leaseDuration);
    }

    /** Atomically validates the latest heartbeat report and reserves capacity for one dispatch. */
    default boolean tryReserve(
            ExecutorInstanceLocation location,
            ExecutorResourceRequirement requirement,
            Instant now
    ) {
        return !ExecutorResourceMetadata.constrained(requirement);
    }

    boolean markOffline(String executorName, String instanceId, String gatewayNodeId, String sessionId);

    List<ExecutorInstanceLocation> listOnline(String executorName, Instant now);

    Optional<ExecutorInstanceLocation> findOnlineInstance(String instanceId, Instant now);
}
