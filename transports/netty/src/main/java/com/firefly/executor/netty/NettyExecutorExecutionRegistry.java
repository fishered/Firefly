package com.firefly.executor.netty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares execution idempotency across every Gateway connection of one executor client. */
final class NettyExecutorExecutionRegistry {
    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(
            NettyExecutorExecutionRegistry.class.getName()
    );
    private final ConcurrentMap<String, CompletableFuture<ExecutorExecutionResult>> executions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, java.util.concurrent.Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ExecutorResultStore resultStore;

    NettyExecutorExecutionRegistry() {
        this(new InMemoryExecutorResultStore());
    }

    NettyExecutorExecutionRegistry(ExecutorResultStore resultStore) {
        this.resultStore = java.util.Objects.requireNonNull(resultStore, "resultStore");
    }

    ExecutionClaim claim(String executionId) {
        java.util.Optional<ExecutorExecutionResult> completed = findCompleted(executionId);
        if (completed.isPresent()) {
            return new ExecutionClaim(CompletableFuture.completedFuture(completed.get()), false);
        }
        CompletableFuture<ExecutorExecutionResult> pending = new CompletableFuture<>();
        CompletableFuture<ExecutorExecutionResult> existing = executions.putIfAbsent(executionId, pending);
        if (existing == null) {
            completed = findCompleted(executionId);
            if (completed.isPresent()) {
                pending.complete(completed.get());
                return new ExecutionClaim(pending, false);
            }
        }
        return existing == null
                ? new ExecutionClaim(pending, true)
                : new ExecutionClaim(existing, false);
    }

    void complete(
            String executionId,
            CompletableFuture<ExecutorExecutionResult> execution,
            ExecutorExecutionResult result
    ) {
        try {
            resultStore.save(executionId, result);
        } catch (RuntimeException storeFailure) {
            log.log(java.util.logging.Level.WARNING,
                    "failed to persist executor idempotency result for " + executionId, storeFailure);
        }
        execution.complete(result);
        runningTasks.remove(executionId);
    }

    void attachTask(String executionId, java.util.concurrent.Future<?> task) {
        runningTasks.put(executionId, task);
        if (cancelled.contains(executionId)) task.cancel(true);
    }

    ExecutorExecutionResult cancel(String executionId, String reason) {
        cancelled.add(executionId);
        java.util.concurrent.Future<?> task = runningTasks.remove(executionId);
        if (task != null) task.cancel(true);
        ExecutorExecutionResult result = new ExecutorExecutionResult(
                "CANCELLED", reason == null || reason.isBlank() ? "cancelled" : reason
        );
        CompletableFuture<ExecutorExecutionResult> execution = executions.computeIfAbsent(
                executionId, ignored -> new CompletableFuture<>()
        );
        complete(executionId, execution, result);
        return result;
    }

    boolean isCancelled(String executionId) {
        return cancelled.contains(executionId);
    }

    private java.util.Optional<ExecutorExecutionResult> findCompleted(String executionId) {
        try {
            return resultStore.find(executionId);
        } catch (RuntimeException storeFailure) {
            log.log(java.util.logging.Level.WARNING,
                    "failed to read executor idempotency result for " + executionId, storeFailure);
            return java.util.Optional.empty();
        }
    }

    void remove(String executionId, CompletableFuture<ExecutorExecutionResult> execution) {
        executions.remove(executionId, execution);
        runningTasks.remove(executionId);
        cancelled.remove(executionId);
    }

    record ExecutionClaim(CompletableFuture<ExecutorExecutionResult> execution, boolean owner) {
    }
}
