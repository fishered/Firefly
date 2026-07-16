package com.firefly.executor.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import com.firefly.domain.ExecutorRoutingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorConnectionRegistryTest {
    @Test
    void selectsRegisteredChannelByExecutorName() {
        NettyExecutorConnectionRegistry registry = new NettyExecutorConnectionRegistry();
        EmbeddedChannel billing = new EmbeddedChannel();
        EmbeddedChannel report = new EmbeddedChannel();

        registry.register("billing-executor", "billing-1", billing);
        registry.register("report-executor", "report-1", report);

        assertEquals(billing, registry.select("billing-executor").orElseThrow());
        assertEquals(report, registry.select("report-executor").orElseThrow());
    }

    @Test
    void unregistersByChannel() {
        NettyExecutorConnectionRegistry registry = new NettyExecutorConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        registry.register("billing-executor", "billing-1", channel);

        assertTrue(registry.unregister(channel).isPresent());
        assertTrue(registry.select("billing-executor").isEmpty());
    }

    @Test
    void newerSessionReplacesTheOldConnectionForTheSameInstance() {
        NettyExecutorConnectionRegistry registry = new NettyExecutorConnectionRegistry();
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        EmbeddedChannel newChannel = new EmbeddedChannel();

        registry.register("billing-executor", "billing-1", "session-old", oldChannel);
        registry.register("billing-executor", "billing-1", "session-new", newChannel);

        assertEquals(newChannel, registry.select("billing-executor").orElseThrow());
        assertEquals("session-new", registry.list().getFirst().sessionId());
        assertTrue(registry.unregister(oldChannel).isEmpty());
    }

    @Test
    void supportsConsistentHashRoutingAndBroadcastSnapshots() {
        NettyExecutorConnectionRegistry registry = new NettyExecutorConnectionRegistry();
        registry.register("billing-executor", "billing-1", new EmbeddedChannel());
        registry.register("billing-executor", "billing-2", new EmbeddedChannel());

        var first = registry.select("billing-executor", ExecutorRoutingStrategy.CONSISTENT_HASH, "customer-42");
        var second = registry.select("billing-executor", ExecutorRoutingStrategy.CONSISTENT_HASH, "customer-42");

        assertEquals(first.orElseThrow().instanceId(), second.orElseThrow().instanceId());
        assertEquals(2, registry.list("billing-executor").size());
    }
}
