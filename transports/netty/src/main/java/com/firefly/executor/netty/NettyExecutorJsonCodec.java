package com.firefly.executor.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Encodes executor protocol messages as newline-delimited JSON frames.
 */
public final class NettyExecutorJsonCodec {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String encode(NettyExecutorMessage message) {
        try {
            return objectMapper.writeValueAsString(new JsonEnvelope(
                    message.messageId(),
                    message.type().name(),
                    message.payload()
            ));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public NettyExecutorMessage decode(String frame) {
        try {
            JsonEnvelope envelope = objectMapper.readValue(frame, JsonEnvelope.class);
            return new NettyExecutorMessage(
                    envelope.messageId(),
                    NettyExecutorMessageType.valueOf(envelope.type()),
                    envelope.payload() == null ? Map.of() : Map.copyOf(envelope.payload())
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid executor json frame", e);
        }
    }

    private record JsonEnvelope(
            String messageId,
            String type,
            Map<String, String> payload
    ) {
    }
}
