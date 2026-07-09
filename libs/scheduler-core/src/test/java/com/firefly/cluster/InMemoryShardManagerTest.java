package com.firefly.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryShardManagerTest {
    @Test
    void onlyOneNodeOwnsShardBeforeLeaseExpires() {
        InMemoryShardManager manager = new InMemoryShardManager();
        Instant now = Instant.parse("2026-07-09T10:00:00Z");

        ShardLease lease = manager.acquire(1, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(manager.acquire(1, "node-2", now.plusSeconds(10), Duration.ofSeconds(30)).isEmpty());
        assertEquals("node-1", lease.ownerNodeId());
        assertEquals(1L, lease.fencingToken());
    }

    @Test
    void expiredLeaseCanBeTakenWithNewFencingToken() {
        InMemoryShardManager manager = new InMemoryShardManager();
        Instant now = Instant.parse("2026-07-09T10:00:00Z");

        manager.acquire(1, "node-1", now, Duration.ofSeconds(30)).orElseThrow();
        ShardLease newLease = manager.acquire(1, "node-2", now.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

        assertEquals("node-2", newLease.ownerNodeId());
        assertEquals(2L, newLease.fencingToken());
        assertTrue(manager.renew(1, "node-1", 1L, now.plusSeconds(32), Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void ownerCanRenewWithCurrentFencingToken() {
        InMemoryShardManager manager = new InMemoryShardManager();
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        ShardLease lease = manager.acquire(1, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        ShardLease renewed = manager.renew(
                1,
                "node-1",
                lease.fencingToken(),
                now.plusSeconds(10),
                Duration.ofSeconds(30)
        ).orElseThrow();

        assertEquals(lease.fencingToken(), renewed.fencingToken());
        assertEquals(now.plusSeconds(40), renewed.leaseUntil());
    }
}
