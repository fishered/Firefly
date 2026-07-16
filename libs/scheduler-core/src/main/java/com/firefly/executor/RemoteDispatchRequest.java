package com.firefly.executor;

import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;

import java.util.Objects;

/** A transport-neutral plan for dispatching one logical execution to remote instances. */
public record RemoteDispatchRequest(
        String executorName,
        String handlerName,
        ExecutionContext context,
        ExecutorDispatchMode dispatchMode,
        ExecutorRoutingStrategy routingStrategy,
        ExecutorCompletionPolicy completionPolicy,
        int shardCount,
        String routingKey,
        String ownerNodeId,
        long fencingToken,
        String rootExecutionId,
        int runAttempt
) {
    public RemoteDispatchRequest(
            String executorName,
            String handlerName,
            ExecutionContext context,
            ExecutorDispatchMode dispatchMode,
            ExecutorRoutingStrategy routingStrategy,
            ExecutorCompletionPolicy completionPolicy,
            int shardCount,
            String routingKey
    ) {
        this(executorName, handlerName, context, dispatchMode, routingStrategy, completionPolicy,
                shardCount, routingKey, "local", 1L, context.executionId(), 0);
    }

    public RemoteDispatchRequest(
            String executorName, String handlerName, ExecutionContext context,
            ExecutorDispatchMode dispatchMode, ExecutorRoutingStrategy routingStrategy,
            ExecutorCompletionPolicy completionPolicy, int shardCount, String routingKey,
            String ownerNodeId, long fencingToken
    ) {
        this(executorName, handlerName, context, dispatchMode, routingStrategy, completionPolicy,
                shardCount, routingKey, ownerNodeId, fencingToken, context.executionId(), 0);
    }

    public RemoteDispatchRequest {
        Objects.requireNonNull(executorName, "executorName");
        Objects.requireNonNull(handlerName, "handlerName");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(dispatchMode, "dispatchMode");
        Objects.requireNonNull(routingStrategy, "routingStrategy");
        Objects.requireNonNull(completionPolicy, "completionPolicy");
        routingKey = Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        Objects.requireNonNull(rootExecutionId, "rootExecutionId");
        if (executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must not be blank");
        }
        if (handlerName.isBlank()) {
            throw new IllegalArgumentException("handlerName must not be blank");
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be greater than 0");
        }
        if (ownerNodeId.isBlank() || fencingToken < 1) {
            throw new IllegalArgumentException("invalid dispatch owner fencing data");
        }
        if (rootExecutionId.isBlank() || runAttempt < 0) {
            throw new IllegalArgumentException("invalid run attempt data");
        }
    }
}
