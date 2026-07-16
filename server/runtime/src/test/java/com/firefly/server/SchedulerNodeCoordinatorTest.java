package com.firefly.server;

import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.InMemoryNodeRegistry;
import com.firefly.cluster.InMemoryShardManager;
import com.firefly.cluster.NodeRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerNodeCoordinatorTest {
    @Test
    void assignsEveryShardToExactlyOneOnlineScheduler() {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryNodeRegistry nodes = new InMemoryNodeRegistry();
        InMemoryShardManager shards = new InMemoryShardManager();
        nodes.register(FireflyNode.builder().nodeId("node-a").roles(Set.of(NodeRole.SCHEDULER))
                .registeredAt(now).lastHeartbeatAt(now).build());
        nodes.register(FireflyNode.builder().nodeId("node-b").roles(Set.of(NodeRole.SCHEDULER))
                .registeredAt(now).lastHeartbeatAt(now).build());
        SchedulerNodeCoordinator first = new SchedulerNodeCoordinator("node-a", true, nodes, shards, clock, 32);
        SchedulerNodeCoordinator second = new SchedulerNodeCoordinator("node-b", true, nodes, shards, clock, 32);

        first.reconcile();
        second.reconcile();

        Set<Integer> all = new HashSet<>(first.ownedShards().keySet());
        assertTrue(java.util.Collections.disjoint(all, second.ownedShards().keySet()));
        all.addAll(second.ownedShards().keySet());
        assertEquals(32, all.size());
        first.close();
        second.close();
    }

    @Test
    void takesOverOnlyAfterTheOldLeaseExpiresAndWithinTheConfiguredWindow() {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryNodeRegistry nodes = new InMemoryNodeRegistry();
        InMemoryShardManager shards = new InMemoryShardManager();
        nodes.register(FireflyNode.builder().nodeId("node-a").roles(Set.of(NodeRole.SCHEDULER))
                .registeredAt(now).lastHeartbeatAt(now).build());
        nodes.register(FireflyNode.builder().nodeId("node-b").roles(Set.of(NodeRole.SCHEDULER))
                .registeredAt(now).lastHeartbeatAt(now).build());
        var options = new com.firefly.cluster.SchedulerCoordinationOptions(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(10)
        );
        SchedulerNodeCoordinator first = new SchedulerNodeCoordinator(
                "node-a", true, nodes, shards, clock, 32, options
        );
        SchedulerNodeCoordinator second = new SchedulerNodeCoordinator(
                "node-b", true, nodes, shards, clock, 32, options
        );
        first.reconcile();
        second.reconcile();

        clock.set(now.plusSeconds(6));
        second.reconcile();
        assertTrue(second.ownedShards().size() < 32);

        clock.set(now.plusSeconds(11));
        second.reconcile();
        assertEquals(32, second.ownedShards().size());
        second.close();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
