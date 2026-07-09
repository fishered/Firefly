package io.github.nishi.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyExecutorJsonCodecTest {
    @Test
    void encodesAndDecodesJsonMessage() {
        NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
        NettyExecutorMessage message = new NettyExecutorMessage(
                "message-1",
                NettyExecutorMessageType.TRIGGER_JOB,
                Map.of("handlerName", "billingHandler", "param.orderId", "A-001")
        );

        NettyExecutorMessage decoded = codec.decode(codec.encode(message));

        assertEquals(message.messageId(), decoded.messageId());
        assertEquals(message.type(), decoded.type());
        assertEquals("billingHandler", decoded.payload().get("handlerName"));
        assertEquals("A-001", decoded.payload().get("param.orderId"));
    }
}
