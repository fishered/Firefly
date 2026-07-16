package com.firefly.executor.netty;

import java.util.Map;
import java.util.Objects;

/**
 * JSON protocol envelope used by the Netty executor transport.
 */
public record NettyExecutorMessage(
        String messageId,
        NettyExecutorMessageType type,
        Map<String, String> payload
) {
    public NettyExecutorMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(type, "type");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
    }
}
