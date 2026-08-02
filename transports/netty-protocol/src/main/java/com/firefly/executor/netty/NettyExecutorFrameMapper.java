package com.firefly.executor.netty;

import java.util.Map;

/** Converts the legacy envelope into a typed body while keeping wire compatibility. */
public final class NettyExecutorFrameMapper {
    public NettyExecutorFrame decode(NettyExecutorMessage message) {
        Map<String, String> payload = message.payload();
        return switch (message.type()) {
            case REGISTER_EXECUTOR -> new RegisterExecutorFrame(
                    required(payload, "executorName"), required(payload, "instanceId"),
                    required(payload, "sessionId"), required(payload, "protocolVersion"));
            case ACK_JOB -> new AckJobFrame(
                    required(payload, "executionId"), required(payload, "instanceId"),
                    required(payload, "sessionId"));
            case REPORT_RESULT -> new ReportResultFrame(
                    required(payload, "executionId"), required(payload, "instanceId"),
                    required(payload, "sessionId"), required(payload, "status"), payload.get("errorMessage"));
            default -> throw new IllegalArgumentException("message type has no typed command frame: " + message.type());
        };
    }

    private static String required(Map<String, String> payload, String field) {
        String value = payload.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing payload field: " + field);
        return value;
    }
}
