package com.firefly.executor.netty;

public record AckJobFrame(String executionId, String instanceId, String sessionId) implements NettyExecutorFrame {
    public AckJobFrame {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId must not be blank");
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
    }
}
