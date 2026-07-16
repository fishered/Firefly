package com.firefly.executor.netty;

import com.firefly.registry.InMemoryJobHandlerRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorClientProtocolTest {
    @Test
    void closesConnectionWhenGatewayOmitsRequiredCapabilities() {
        var workers = Executors.newSingleThreadExecutor();
        try {
            EmbeddedChannel channel = new EmbeddedChannel(new NettyExecutorClientHandler(
                    "orders", "instance-a", "session-a", "", "orders-service",
                    Duration.ofSeconds(10), new InMemoryJobHandlerRegistry(), workers,
                    new NettyExecutorJsonCodec(), Clock.systemUTC(), new NettyExecutorExecutionRegistry(),
                    ignored -> { }
            ));
            assertTrue(channel.isActive());
            channel.readOutbound();
            channel.writeInbound(new NettyExecutorJsonCodec().encode(new NettyExecutorMessage(
                    "registered-1", NettyExecutorMessageType.REGISTERED,
                    Map.of("protocolVersion", "1", "capabilities", "TARGET_ACK")
            )));
            channel.runPendingTasks();

            assertFalse(channel.isActive());
            channel.finishAndReleaseAll();
        } finally {
            workers.shutdownNow();
        }
    }
}
