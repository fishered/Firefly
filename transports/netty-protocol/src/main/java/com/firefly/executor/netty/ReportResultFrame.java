package com.firefly.executor.netty;

public record ReportResultFrame(
        String executionId,
        String instanceId,
        String sessionId,
        String status,
        String errorMessage,
        Integer shardIndex,
        Long inputRecords,
        Long outputRecords,
        String checkpointId,
        String checkpointChecksum
) implements NettyExecutorFrame {
    public ReportResultFrame(String executionId, String instanceId, String sessionId, String status, String errorMessage) {
        this(executionId, instanceId, sessionId, status, errorMessage, null, null, null, "", "");
    }
    public ReportResultFrame {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId must not be blank");
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        if (shardIndex != null && shardIndex < 0) throw new IllegalArgumentException("shardIndex must not be negative");
        if (inputRecords != null && inputRecords < 0 || outputRecords != null && outputRecords < 0) throw new IllegalArgumentException("record counts must not be negative");
        checkpointId = checkpointId == null ? "" : checkpointId;
        checkpointChecksum = checkpointChecksum == null ? "" : checkpointChecksum;
        if (checkpointChecksum.length() > 256) throw new IllegalArgumentException("checkpoint checksum too long");
    }
}
