package com.firefly.server;

import com.firefly.governance.InMemoryTenantCapacityStore;
import com.firefly.governance.InMemoryTenantQuotaStore;
import com.firefly.governance.TenantCapacityObservationBuffer;
import com.firefly.governance.TenantCapacityResource;
import com.firefly.governance.TenantDefinition;
import com.firefly.metrics.SchedulerMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantCapacitySamplerTest {
    @Test
    void authoritativeOwnerSamplesAndFlushesWithoutTouchingAdmission() {
        Instant start = Instant.parse("2026-11-07T08:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryTenantQuotaStore quotas = new InMemoryTenantQuotaStore();
        quotas.save(new TenantDefinition("payments", true, 1, 10, 100, 100, 100, 100, 0, start), 0, start);
        InMemoryTenantCapacityStore capacity = new InMemoryTenantCapacityStore();
        SchedulerMetrics metrics = new SchedulerMetrics();
        TenantCapacityObservationBuffer buffer = new TenantCapacityObservationBuffer(
                capacity, metrics, "node-a", "1.2.1", Duration.ofMinutes(5), 100
        );
        TenantCapacitySampler sampler = new TenantCapacitySampler(quotas, capacity, buffer, metrics, clock,
                () -> true, Duration.ofMinutes(1), Duration.ofDays(7), 100);

        sampler.sampleNow();
        clock.instant = start.plus(Duration.ofMinutes(5));
        sampler.sampleNow();

        assertEquals(1, capacity.baseline("payments", start, clock.instant(), Duration.ofMinutes(5))
                .observedBuckets());
        assertEquals(0, capacity.baseline("payments", start, clock.instant(), Duration.ofMinutes(5))
                .resource(TenantCapacityResource.CONCURRENCY).peak());
    }

    @Test
    @Tag("production-readiness")
    void oneThousandTenantReportQueriesRemainBounded() {
        Instant now = Instant.parse("2026-11-07T08:00:00Z");
        InMemoryTenantCapacityStore capacity = new InMemoryTenantCapacityStore();
        for (int tenant = 0; tenant < 1_000; tenant++) {
            String tenantId = "tenant-" + tenant;
            capacity.merge(new com.firefly.governance.TenantCapacityFlush("flush-" + tenant,
                    new com.firefly.governance.TenantCapacitySample(tenantId, now.minus(Duration.ofMinutes(5)),
                            1, 1, 1, "1.2.1", "1.2.1", tenant % 100, tenant, tenant * 2L,
                            tenant * 3L, tenant % 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), now);
        }
        long started = System.nanoTime();
        for (int tenant = 0; tenant < 1_000; tenant++) {
            capacity.baseline("tenant-" + tenant, now.minus(Duration.ofDays(7)), now,
                    Duration.ofMinutes(5));
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(true, elapsedMillis < 2_000, "1000-tenant report exceeded 2 seconds: " + elapsedMillis);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
