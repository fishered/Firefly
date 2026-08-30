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
                    required(payload, "sessionId"), required(payload, "status"), payload.get("errorMessage"),
                    optionalInt(payload, "shardIndex"), optionalLong(payload, "inputRecords"),
                    optionalLong(payload, "outputRecords"), payload.get("checkpointId"),
                    payload.get("checkpointChecksum"));
            default -> throw new IllegalArgumentException("message type has no typed command frame: " + message.type());
        };
    }

    private static String required(Map<String, String> payload, String field) {
        String value = payload.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing payload field: " + field);
        return value;
    }

    private static Integer optionalInt(Map<String, String> payload, String field) {
        String value = payload.get(field);
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid " + field, e); }
    }

    private static Long optionalLong(Map<String, String> payload, String field) {
        String value = payload.get(field);
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid " + field, e); }
    }
}
