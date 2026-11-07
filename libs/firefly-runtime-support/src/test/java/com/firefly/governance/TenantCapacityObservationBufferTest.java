package com.firefly.governance;

import com.firefly.metrics.SchedulerMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TenantCapacityObservationBufferTest {
    @Test
    void flushesClosedBucketsIdempotentlyAndMergesMaxima() {
        Instant start = Instant.parse("2026-11-07T08:00:00Z");
        InMemoryTenantCapacityStore store = new InMemoryTenantCapacityStore();
        SchedulerMetrics metrics = new SchedulerMetrics();
        TenantCapacityObservationBuffer buffer = new TenantCapacityObservationBuffer(
                store, metrics, "node-a", "1.2.1", Duration.ofMinutes(5), 10
        );
        TenantDefinition policy = new TenantDefinition("payments", true, 1, 10, 100, 100,
                100, 100, 4, start);
        buffer.observeSnapshot(policy, new TenantUsage("payments", 5, 20, 30, 40, start, 50, 1), start);
        buffer.observeSnapshot(policy, new TenantUsage("payments", 9, 30, 20, 60, start, 70, 2),
                start.plusSeconds(30));

        assertEquals(1, buffer.flushClosed(start.plus(Duration.ofMinutes(5))));
        assertEquals(0, buffer.flushClosed(start.plus(Duration.ofMinutes(10))));

        TenantCapacityBaseline baseline = store.baseline("payments", start,
                start.plus(Duration.ofMinutes(5)), Duration.ofMinutes(5));
        assertEquals(1, baseline.observedBuckets());
        assertEquals(9, baseline.resource(TenantCapacityResource.CONCURRENCY).peak());
        assertEquals(0.5, baseline.resource(TenantCapacityResource.CONCURRENCY).saturationRatio());
    }

    @Test
    void dropsNewBucketsWhenBoundIsReachedWithoutThrowing() {
        Instant start = Instant.parse("2026-11-07T08:00:00Z");
        SchedulerMetrics metrics = new SchedulerMetrics();
        TenantCapacityObservationBuffer buffer = new TenantCapacityObservationBuffer(
                new InMemoryTenantCapacityStore(), metrics, "node-a", "1.2.1",
                Duration.ofMinutes(5), 1
        );
        TenantDefinition first = new TenantDefinition("first", true, 1, 0, 0, 0, 0, 0, 1, start);
        TenantDefinition second = new TenantDefinition("second", true, 1, 0, 0, 0, 0, 0, 1, start);
        buffer.observeSnapshot(first, TenantUsage.empty("first", start), start);
        assertDoesNotThrow(() -> buffer.observeSnapshot(second, TenantUsage.empty("second", start), start));
        assertEquals(1, buffer.pendingBuckets());
        assertEquals(1, metrics.tenantCapacityObservationsDropped().get("buffer_full"));
    }
}
