package com.firefly.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutorInstanceDirectory {
    void register(ExecutorInstanceLocation location);

    boolean heartbeat(
            String executorName, String instanceId, String gatewayNodeId,
            String sessionId, Instant now, Duration leaseDuration
    );

    boolean markOffline(String executorName, String instanceId, String gatewayNodeId, String sessionId);

    List<ExecutorInstanceLocation> listOnline(String executorName, Instant now);

    Optional<ExecutorInstanceLocation> findOnlineInstance(String instanceId, Instant now);
}
