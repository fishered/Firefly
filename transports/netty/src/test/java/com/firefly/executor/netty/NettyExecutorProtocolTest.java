package com.firefly.executor.netty;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.executor.InMemoryExecutorRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NettyExecutorProtocolTest {
    @Test
    void rejectsUnsupportedProtocolVersionsBeforeRegistration() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = channel(executors);
        channel.writeInbound(registration("99"));

        String frame = channel.readOutbound();
        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(frame.trim());
        assertEquals(NettyExecutorMessageType.REGISTER_REJECTED, response.type());
        assertFalse(executors.find("orders", "instance-a").isPresent());
        channel.finishAndReleaseAll();
    }

    @Test
    void negotiatesTheCurrentProtocolVersion() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = channel(executors);
        channel.writeInbound(registration(Integer.toString(NettyExecutorProtocol.CURRENT_VERSION)));

        String frame = channel.readOutbound();
        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(frame.trim());
        assertEquals(NettyExecutorMessageType.REGISTERED, response.type());
        assertEquals(Integer.toString(NettyExecutorProtocol.CURRENT_VERSION),
                response.payload().get("protocolVersion"));
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsClientsThatDeclareButOmitRequiredCapabilities() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = channel(executors);
        NettyExecutorMessage request = new NettyExecutorMessage(
                "register-2", NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of(
                        "executorName", "orders",
                        "instanceId", "instance-a",
                        "sessionId", "session-a",
                        "serviceName", "orders-service",
                        "protocolVersion", "1",
                        "capabilities", "TARGET_ACK"
                )
        );
        channel.writeInbound(new NettyExecutorJsonCodec().encode(request));

        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(
                ((String) channel.readOutbound()).trim()
        );
        assertEquals(NettyExecutorMessageType.REGISTER_REJECTED, response.type());
        assertFalse(executors.find("orders", "instance-a").isPresent());
        channel.finishAndReleaseAll();
    }

    private EmbeddedChannel channel(InMemoryExecutorRegistry executors) {
        return new EmbeddedChannel(new NettyExecutorGatewayHandler(
                executors, new NettyExecutorConnectionRegistry(), new NettyExecutorJsonCodec(), Clock.systemUTC(),
                new InMemorySchedulerCatalog(), true, "gateway-a", new InMemoryExecutionRepository(),
                (executionId, acknowledgedAt) -> { }, Runnable::run, "", (executionId, timeout) -> { }
        ));
    }

    private String registration(String version) {
        return new NettyExecutorJsonCodec().encode(new NettyExecutorMessage(
                "register-1", NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of(
                        "executorName", "orders",
                        "instanceId", "instance-a",
                        "sessionId", "session-a",
                        "serviceName", "orders-service",
                        "protocolVersion", version
                )
        ));
    }
}
