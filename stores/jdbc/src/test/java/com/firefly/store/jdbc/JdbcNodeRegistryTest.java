package com.firefly.store.jdbc;

import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.NodeRole;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcNodeRegistryTest {
    @Test
    void registersAndHeartbeatsNodes() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcNodeRegistry registry = new JdbcNodeRegistry(dataSource);
        Instant now = Instant.parse("2026-07-09T10:00:00Z");

        registry.register(FireflyNode.builder()
                .nodeId("node-1")
                .roles(Set.of(NodeRole.SCHEDULER, NodeRole.GATEWAY))
                .registeredAt(now.minusSeconds(60))
                .lastHeartbeatAt(now.minusSeconds(40))
                .metadata(Map.of("zone", "az-a"))
                .build());

        assertTrue(registry.find("node-1").isPresent());
        assertTrue(registry.listOnline(now, Duration.ofSeconds(30)).isEmpty());

        registry.heartbeat("node-1", now);

        assertEquals(List.of("node-1"), registry.listOnline(now, Duration.ofSeconds(30)).stream()
                .map(FireflyNode::nodeId)
                .toList());
        assertEquals("az-a", registry.find("node-1").orElseThrow().metadata().get("zone"));
    }
}
