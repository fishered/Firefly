package com.firefly.store.jdbc;

import com.firefly.cluster.ShardLease;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcShardManagerTest {
    @Test
    void preventsTakeoverBeforeLeaseExpires() {
        JdbcShardManager manager = new JdbcShardManager(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-09T10:00:00Z");

        ShardLease lease = manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(manager.acquire(3, "node-2", now.plusSeconds(10), Duration.ofSeconds(30)).isEmpty());
        assertEquals("node-1", lease.ownerNodeId());
        assertEquals(1L, lease.fencingToken());
    }

    @Test
    void takeoverAfterExpiryIncrementsFencingToken() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcShardManager manager = new JdbcShardManager(dataSource);
        Instant now = Instant.parse("2026-07-09T10:00:00Z");

        manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();
        ShardLease newLease = manager.acquire(3, "node-2", now.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

        assertEquals("node-2", newLease.ownerNodeId());
        assertEquals(2L, newLease.fencingToken());
        assertTrue(manager.renew(3, "node-1", 1L, now.plusSeconds(32), Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void releaseAllowsNewOwnerWithNewToken() {
        JdbcShardManager manager = new JdbcShardManager(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        ShardLease lease = manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(manager.release(3, "node-1", lease.fencingToken()));
        ShardLease newLease = manager.acquire(3, "node-2", now.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();

        assertEquals(2L, newLease.fencingToken());
    }
}
