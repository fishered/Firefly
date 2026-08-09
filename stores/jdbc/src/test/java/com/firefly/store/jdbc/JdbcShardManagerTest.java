package com.firefly.store.jdbc;

import com.firefly.cluster.ShardLease;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcShardManagerTest {
    @Test
    void preventsTakeoverBeforeLeaseExpires() {
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcShardManager manager = new JdbcShardManager(
                JdbcTestSupport.dataSource(), ignored -> databaseNow.get()
        );

        ShardLease lease = manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        databaseNow.set(now.plusSeconds(10));
        assertTrue(manager.acquire(3, "node-2", Instant.EPOCH, Duration.ofSeconds(30)).isEmpty());
        assertEquals("node-1", lease.ownerNodeId());
        assertEquals(1L, lease.fencingToken());
    }

    @Test
    void takeoverAfterExpiryIncrementsFencingToken() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcShardManager manager = new JdbcShardManager(dataSource, ignored -> databaseNow.get());

        manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();
        databaseNow.set(now.plusSeconds(31));
        ShardLease newLease = manager.acquire(3, "node-2", Instant.EPOCH, Duration.ofSeconds(30)).orElseThrow();

        assertEquals("node-2", newLease.ownerNodeId());
        assertEquals(2L, newLease.fencingToken());
        databaseNow.set(now.plusSeconds(32));
        assertTrue(manager.renew(3, "node-1", 1L, Instant.EPOCH, Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void releaseAllowsNewOwnerWithNewToken() {
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcShardManager manager = new JdbcShardManager(
                JdbcTestSupport.dataSource(), ignored -> databaseNow.get()
        );
        ShardLease lease = manager.acquire(3, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(manager.release(3, "node-1", lease.fencingToken()));
        databaseNow.set(now.plusSeconds(1));
        ShardLease newLease = manager.acquire(3, "node-2", Instant.EPOCH, Duration.ofSeconds(30)).orElseThrow();

        assertEquals(2L, newLease.fencingToken());
    }

    @Test
    void renewsMultipleOwnedShardsUsingOneBatchOperation() {
        Instant now = Instant.parse("2026-07-09T10:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcShardManager manager = new JdbcShardManager(
                JdbcTestSupport.dataSource(), ignored -> databaseNow.get()
        );
        ShardLease first = manager.acquire(1, "node-1", now, Duration.ofSeconds(30)).orElseThrow();
        ShardLease second = manager.acquire(2, "node-1", now, Duration.ofSeconds(30)).orElseThrow();

        databaseNow.set(now.plusSeconds(5));
        Map<Integer, ShardLease> renewed = manager.renewAll(
                List.of(first, second), "node-1", now.plusSeconds(5), Duration.ofSeconds(30)
        );

        assertEquals(2, renewed.size());
        assertEquals(now.plusSeconds(35), renewed.get(1).leaseUntil());
        assertEquals(now.plusSeconds(35), renewed.get(2).leaseUntil());
    }
}
