package com.firefly.executor.netty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Stable JSON shape isolated from the public protocol domain model. */
@JsonIgnoreProperties(ignoreUnknown = true)
record NettyExecutorWireMessage(String messageId, String type, Map<String, String> payload) {
    static NettyExecutorWireMessage from(NettyExecutorMessage message) {
        return new NettyExecutorWireMessage(
                message.messageId(), message.type().name(), message.payload()
        );
    }

    NettyExecutorMessage toMessage() {
        return new NettyExecutorMessage(
                messageId,
                NettyExecutorMessageType.valueOf(type),
                payload == null ? Map.of() : Map.copyOf(payload)
        );
    }
}
