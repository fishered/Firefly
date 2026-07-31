package com.firefly.executor.netty;

import java.util.Map;
import java.util.Objects;

/**
 * Protocol message used by the Netty executor transport.
 *
 * <p>The record is not {@link java.io.Serializable}. Its stable JSON wire shape is
 * owned by {@link NettyExecutorJsonCodec}, so Java implementation changes cannot
 * silently switch the network protocol to native object serialization.</p>
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
