package com.firefly.executor.netty;

public record RegisterExecutorFrame(
        String executorName,
        String instanceId,
        String sessionId,
        String protocolVersion
) implements NettyExecutorFrame {
    public RegisterExecutorFrame {
        require(executorName, "executorName");
        require(instanceId, "instanceId");
        require(sessionId, "sessionId");
        require(protocolVersion, "protocolVersion");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
