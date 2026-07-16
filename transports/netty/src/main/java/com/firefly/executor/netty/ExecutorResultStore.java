package com.firefly.executor.netty;

import java.util.Optional;

/**
 * SPI for reusing completed executor results after reconnect or process restart.
 * Implementations must make save durable before returning when restart deduplication is required.
 */
public interface ExecutorResultStore {
    Optional<ExecutorExecutionResult> find(String executionId);

    void save(String executionId, ExecutorExecutionResult result);
}
