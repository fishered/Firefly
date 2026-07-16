package com.firefly.executor;

import com.firefly.domain.ExecutorInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tracks executor instances and their heartbeat-derived online state.
 */
public interface ExecutorRegistry {
    void register(ExecutorInstance instance);

    Optional<ExecutorInstance> find(String executorName, String instanceId);

    boolean heartbeat(String executorName, String instanceId, Instant heartbeatAt);

    boolean markOffline(String executorName, String instanceId);

    List<ExecutorInstance> listAll();

    List<ExecutorInstance> listOnline(String executorName, Instant now, Duration heartbeatTimeout);
}
