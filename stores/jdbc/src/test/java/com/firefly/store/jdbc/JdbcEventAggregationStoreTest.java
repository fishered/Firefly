package com.firefly.store.jdbc;

import com.firefly.trigger.EventAggregationPolicy;
import com.firefly.trigger.EventTrigger;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEventAggregationStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void persistsWindowsAndReleasesExpiredClaimsAcrossStoreInstances() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcEventAggregationStore first = new JdbcEventAggregationStore(dataSource);
        JdbcEventAggregationStore second = new JdbcEventAggregationStore(dataSource);
        EventAggregationPolicy policy = new EventAggregationPolicy(
                "customer-42", Duration.ofMillis(500), Duration.ofSeconds(5)
        );

        assertTrue(first.add("orders", event("e1", "k1", "one", NOW), policy,
                NOW, "worker-1", LEASE).isEmpty());
        assertTrue(second.add("orders", event("e2", "k2", "two", NOW.plusMillis(100)), policy,
                NOW.plusMillis(100), "worker-2", LEASE).isEmpty());
        assertTrue(first.claimDue(NOW.plusMillis(599), "worker-1", LEASE, 10).isEmpty());

        var claimed = first.claimDue(NOW.plusMillis(600), "worker-1", LEASE, 10);
        assertEquals(1, claimed.size());
        assertEquals(2, claimed.getFirst().eventCount());
        assertEquals("two", claimed.getFirst().latestPayload());
        assertEquals(2, claimed.getFirst().idempotencyKeys().size());
        assertTrue(second.claimDue(NOW.plusMillis(600), "worker-2", LEASE, 10).isEmpty());

        assertTrue(first.release(claimed.getFirst().aggregateId(), "worker-1", NOW.plusMillis(700)));
        var reclaimed = second.claimDue(NOW.plusMillis(700), "worker-2", LEASE, 10);
        assertEquals(claimed.getFirst().aggregateId(), reclaimed.getFirst().aggregateId());
        assertTrue(second.complete(reclaimed.getFirst().aggregateId(), "worker-2"));
        assertTrue(new JdbcEventAggregationStore(dataSource)
                .claimDue(NOW.plusSeconds(60), "worker-3", LEASE, 10).isEmpty());
    }

    private EventTrigger event(String eventId, String key, String payload, Instant receivedAt) {
        return new EventTrigger(eventId, "order", key, payload, receivedAt,
                EventTrigger.TriggerStatus.RECEIVED, null);
    }
}
