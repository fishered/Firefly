package com.firefly.executor.netty;

import io.netty.channel.embedded.EmbeddedChannel;
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
}
