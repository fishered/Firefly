package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorJsonCodecTest {
    @Test
    void encodesAndDecodesJsonMessage() {
        NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
        NettyExecutorMessage message = new NettyExecutorMessage(
                "message-1",
                NettyExecutorMessageType.TRIGGER_JOB,
                Map.of("handlerName", "billingHandler", "param.orderId", "A-001")
        );

        String frame = codec.encode(message);
        NettyExecutorMessage decoded = codec.decode(frame);

        assertTrue(frame.contains("\"messageId\":\"message-1\""));
        assertTrue(frame.contains("\"type\":\"TRIGGER_JOB\""));
        assertFalse(frame.contains("@class"));
        assertEquals(message.messageId(), decoded.messageId());
        assertEquals(message.type(), decoded.type());
        assertEquals("billingHandler", decoded.payload().get("handlerName"));
        assertEquals("A-001", decoded.payload().get("param.orderId"));
    }

    @Test
    void acceptsUnknownFieldsForForwardCompatibility() {
        NettyExecutorMessage decoded = new NettyExecutorJsonCodec().decode("""
                {"messageId":"message-1","type":"HEARTBEAT","payload":{},"futureField":"ignored"}
                """);

        assertEquals(NettyExecutorMessageType.HEARTBEAT, decoded.type());
    }

    @Test
    void treatsMissingPayloadAsEmptyForLegacyFrames() {
        NettyExecutorMessage decoded = new NettyExecutorJsonCodec().decode(
                "{\"messageId\":\"message-1\",\"type\":\"HEARTBEAT\"}"
        );

        assertEquals(Map.of(), decoded.payload());
    }

    @Test
    void rejectsUnknownMessageTypes() {
        assertThrows(IllegalArgumentException.class, () -> new NettyExecutorJsonCodec().decode(
                "{\"messageId\":\"message-1\",\"type\":\"FUTURE_TYPE\",\"payload\":{}}"
        ));
    }
}
