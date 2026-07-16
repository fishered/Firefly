package com.firefly.executor.netty;

/** Persistable terminal result used by executor-side idempotency stores. */
public record ExecutorExecutionResult(String status, String errorMessage) {
    public ExecutorExecutionResult {
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("status must be SUCCEEDED or FAILED");
        }
        errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
