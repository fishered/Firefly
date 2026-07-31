package com.firefly.executor.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;

/**
 * Encodes executor protocol messages as newline-delimited JSON frames.
 *
 * <p>This codec is the only serialization boundary for {@link NettyExecutorMessage};
 * protocol messages never use Java native serialization.</p>
 */
public final class NettyExecutorJsonCodec {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String encode(NettyExecutorMessage message) {
        try {
            return objectMapper.writeValueAsString(NettyExecutorWireMessage.from(message));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public NettyExecutorMessage decode(String frame) {
        try {
            return objectMapper.readValue(frame, NettyExecutorWireMessage.class).toMessage();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid executor json frame", e);
        }
    }
}
