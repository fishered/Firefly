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
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorProtocolTest {
    @Test
    void rejectsUnsupportedProtocolVersionsBeforeRegistration() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        com.firefly.metrics.SchedulerMetrics metrics = new com.firefly.metrics.SchedulerMetrics();
        EmbeddedChannel channel = channel(executors, metrics);
        channel.writeInbound(registration("99"));

        String frame = channel.readOutbound();
        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(frame.trim());
        assertEquals(NettyExecutorMessageType.REGISTER_REJECTED, response.type());
        assertEquals("UNSUPPORTED_PROTOCOL_VERSION", response.payload().get("reasonCode"));
        assertEquals(1L, metrics.snapshot().executorRegistrationRejections());
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
    void oldExecutorConnectsToNewGatewayWithCapabilityDowngrade() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = channel(executors);
        channel.writeInbound(registration("1"));

        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(
                ((String) channel.readOutbound()).trim()
        );
        assertEquals("1", response.payload().get("protocolVersion"));
        assertFalse(NettyExecutorProtocol.decodeCapabilities(response.payload().get("capabilities"))
                .contains("CANCELLATION"));
        assertTrue(executors.find("orders", "instance-a").isPresent());
        channel.finishAndReleaseAll();
    }

    @Test
    void newExecutorCanOmitOptionalCancellationCapabilityDuringRollingUpgrade() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = channel(executors);
        NettyExecutorMessage request = new NettyExecutorMessage(
                "register-rolling", NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of(
                        "executorName", "orders", "instanceId", "instance-a",
                        "sessionId", "session-a", "serviceName", "orders-service",
                        "protocolVersion", "2", "capabilities", "TARGET_ACK,RESULT_REPORT"
                )
        );
        channel.writeInbound(new NettyExecutorJsonCodec().encode(request));

        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(
                ((String) channel.readOutbound()).trim()
        );
        assertEquals(NettyExecutorMessageType.REGISTERED, response.type());
        assertEquals("2", response.payload().get("protocolVersion"));
        assertFalse(NettyExecutorProtocol.decodeCapabilities(response.payload().get("capabilities"))
                .contains("CANCELLATION"));
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

    @Test
    void drainingGatewayRejectsNewExecutorRegistrations() {
        InMemoryExecutorRegistry executors = new InMemoryExecutorRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyExecutorGatewayHandler(
                executors, new NettyExecutorConnectionRegistry(), new NettyExecutorJsonCodec(), Clock.systemUTC(),
                new InMemorySchedulerCatalog(), true, "gateway-a", new InMemoryExecutionRepository(),
                (executionId, acknowledgedAt) -> { }, Runnable::run, "", (executionId, timeout) -> { },
                new com.firefly.metrics.SchedulerMetrics(),
                new com.firefly.executor.InMemoryExecutorInstanceDirectory(), "",
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(90), () -> false
        ));

        channel.writeInbound(registration("2"));

        NettyExecutorMessage response = new NettyExecutorJsonCodec().decode(
                ((String) channel.readOutbound()).trim()
        );
        assertEquals(NettyExecutorMessageType.REGISTER_REJECTED, response.type());
        assertEquals("NODE_DRAINING", response.payload().get("reasonCode"));
        assertFalse(executors.find("orders", "instance-a").isPresent());
        channel.finishAndReleaseAll();
    }

    private EmbeddedChannel channel(InMemoryExecutorRegistry executors) {
        return channel(executors, new com.firefly.metrics.SchedulerMetrics());
    }

    private EmbeddedChannel channel(
            InMemoryExecutorRegistry executors,
            com.firefly.metrics.SchedulerMetrics metrics
    ) {
        return new EmbeddedChannel(new NettyExecutorGatewayHandler(
                executors, new NettyExecutorConnectionRegistry(), new NettyExecutorJsonCodec(), Clock.systemUTC(),
                new InMemorySchedulerCatalog(), true, "gateway-a", new InMemoryExecutionRepository(),
                (executionId, acknowledgedAt) -> { }, Runnable::run, "", (executionId, timeout) -> { }, metrics
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
