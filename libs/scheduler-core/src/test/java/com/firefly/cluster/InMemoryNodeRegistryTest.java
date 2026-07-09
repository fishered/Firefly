package com.firefly.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryNodeRegistryTest {
    @Test
    void tracksOnlineNodesByHeartbeatLease() {
        InMemoryNodeRegistry registry = new InMemoryNodeRegistry();
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        registry.register(FireflyNode.builder()
                .nodeId("node-1")
                .roles(Set.of(NodeRole.SCHEDULER, NodeRole.GATEWAY))
                .registeredAt(now.minusSeconds(60))
                .lastHeartbeatAt(now.minusSeconds(20))
                .build());
        registry.register(FireflyNode.builder()
                .nodeId("node-2")
                .roles(Set.of(NodeRole.STANDBY))
                .registeredAt(now.minusSeconds(60))
                .lastHeartbeatAt(now.minusSeconds(120))
                .build());

        assertEquals(List.of("node-1"), registry.listOnline(now, Duration.ofSeconds(30)).stream()
                .map(FireflyNode::nodeId)
                .toList());

        registry.heartbeat("node-2", now);
        assertEquals(2, registry.listOnline(now, Duration.ofSeconds(30)).size());

        registry.markOffline("node-1");
        assertEquals("node-2", registry.listOnline(now, Duration.ofSeconds(30)).getFirst().nodeId());
    }
}
