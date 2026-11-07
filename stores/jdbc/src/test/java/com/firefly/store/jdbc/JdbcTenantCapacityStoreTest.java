package com.firefly.store.jdbc;

import com.firefly.governance.TenantCapacityBaseline;
import com.firefly.governance.TenantCapacityFlush;
import com.firefly.governance.TenantCapacityResource;
import com.firefly.governance.TenantCapacitySample;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTenantCapacityStoreTest {
    @Test
    void deduplicatesFlushAndAggregatesMaximaAndCounters() {
        JdbcTenantCapacityStore store = new JdbcTenantCapacityStore(JdbcTestSupport.dataSource());
        Instant start = Instant.parse("2026-11-07T08:00:00Z");
        TenantCapacityFlush first = new TenantCapacityFlush("node-a:payments:1", sample(start, 4, 8, 1));

        assertTrue(store.merge(first, start.plusSeconds(10)));
        assertFalse(store.merge(first, start.plusSeconds(20)));
        assertTrue(store.merge(new TenantCapacityFlush("node-b:payments:1", sample(start, 4, 10, 2)),
                start.plusSeconds(30)));

        TenantCapacityBaseline baseline = store.baseline("payments", start,
                start.plus(Duration.ofMinutes(5)), Duration.ofMinutes(5));
        assertEquals(1, baseline.observedBuckets());
        assertEquals(10, baseline.resource(TenantCapacityResource.CONCURRENCY).peak());
        assertEquals(3, baseline.resource(TenantCapacityResource.CONCURRENCY).rejections());
        assertEquals(4, baseline.minPolicyVersion());
        assertFalse(baseline.mixedRuntimeVersion());
    }

    @Test
    void retentionDeletionIsBounded() {
        JdbcTenantCapacityStore store = new JdbcTenantCapacityStore(JdbcTestSupport.dataSource());
        Instant start = Instant.parse("2026-11-07T08:00:00Z");
        store.merge(new TenantCapacityFlush("a", sample(start, 4, 1, 0)), start);
        store.merge(new TenantCapacityFlush("b", sample(start.plus(Duration.ofMinutes(5)), 4, 1, 0)), start);

        assertEquals(1, store.deleteBefore(start.plus(Duration.ofHours(1)), 1));
        assertEquals(1, store.deleteBefore(start.plus(Duration.ofHours(1)), 10));
        assertEquals(0, store.deleteBefore(start.plus(Duration.ofHours(1)), 10));
    }

    private static TenantCapacitySample sample(Instant start, long policyVersion, long active, long rejected) {
        return new TenantCapacitySample("payments", start, 1, policyVersion, policyVersion,
                "1.2.1", "1.2.1", active, 20, 30, 40, 50,
                active >= 9 ? 1 : 0, 0, 0, 0, 0,
                rejected, 0, 0, 0, 0, 0);
    }
}
