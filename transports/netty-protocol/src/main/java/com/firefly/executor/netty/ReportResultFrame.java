package com.firefly.executor.netty;

public record ReportResultFrame(
        String executionId,
        String instanceId,
        String sessionId,
        String status,
        String errorMessage
) implements NettyExecutorFrame {
    public ReportResultFrame {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId must not be blank");
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
    }
}
