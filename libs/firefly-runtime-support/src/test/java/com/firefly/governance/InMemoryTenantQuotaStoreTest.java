package com.firefly.governance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTenantQuotaStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Test
    void appliesDynamicQuotaAndReturnsStableRejectionReasons() {
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
        TenantDefinition tenant = store.save(policy("blue", 1, 100, 1_000, 2, 2), 0, NOW);

        var first = store.tryReserve(request("r1", "blue", 1, 60, 400, 1, 1),
                NOW, Duration.ofMinutes(1));
        assertTrue(first.admitted());
        assertEquals(TenantAdmissionReason.ADMITTED, first.reason());
        assertEquals(TenantAdmissionReason.IDEMPOTENT_REPLAY,
                store.tryReserve(request("r1", "blue", 1, 60, 400, 1, 1),
                        NOW, Duration.ofMinutes(1)).reason());

        assertEquals(TenantAdmissionReason.CONCURRENCY_QUOTA_EXCEEDED,
                store.tryReserve(request("r2", "blue", 1, 1, 1, 0, 0),
                        NOW, Duration.ofMinutes(1)).reason());

        TenantDefinition expanded = new TenantDefinition("blue", true, 3, 2, 100, 1_000,
                2, 2, tenant.version(), NOW);
        store.save(expanded, tenant.version(), NOW.plusSeconds(1));
        assertTrue(store.tryReserve(request("r2", "blue", 1, 40, 600, 1, 1),
                NOW.plusSeconds(1), Duration.ofMinutes(1)).admitted());
        assertEquals(TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED,
                store.tryReserve(request("r3", "blue", 0, 0, 0, 0, 2),
                        NOW.plusSeconds(1), Duration.ofMinutes(1)).reason());
    }

    @Test
    void reclaimsLeasesWithoutReleasingTriggerRateUsage() {
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
        store.save(policy("blue", 1, 0, 0, 0, 1), 0, NOW);
        assertTrue(store.tryReserve(request("r1", "blue", 1, 0, 0, 0, 1),
                NOW, Duration.ofMillis(400)).admitted());

        assertEquals(1, store.reclaimExpired(NOW.plusMillis(500), 10));
        assertEquals(0, store.usage("blue", NOW.plusMillis(500)).activeExecutions());
        assertEquals(TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED,
                store.tryReserve(request("r2", "blue", 0, 0, 0, 0, 1),
                        NOW.plusMillis(500), Duration.ofSeconds(5)).reason());
        assertTrue(store.tryReserve(request("r3", "blue", 1, 0, 0, 0, 1),
                NOW.plusSeconds(6), Duration.ofSeconds(5)).admitted());
    }

    @Test
    void preservesDefaultTenantCompatibilityAndRejectsUnknownTenants() {
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
        assertEquals(TenantDefinition.DEFAULT_TENANT_ID, store.find("").orElseThrow().tenantId());
        assertTrue(store.tryReserve(request("legacy", null, 1, 0, 0, 0, 0),
                NOW, Duration.ofSeconds(30)).admitted());
        assertEquals(TenantAdmissionReason.TENANT_NOT_FOUND,
                store.tryReserve(request("unknown", "missing", 1, 0, 0, 0, 0),
                        NOW, Duration.ofSeconds(30)).reason());
    }

    @Test
    void clockSkewCannotRollTheGlobalTriggerWindowBackwards() {
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
        store.save(policy("blue", 0, 0, 0, 0, 1), 0, NOW);
        assertTrue(store.tryReserve(request("newer", "blue", 0, 0, 0, 0, 1),
                NOW.plusSeconds(10), Duration.ofSeconds(5)).admitted());
        assertEquals(TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED,
                store.tryReserve(request("skewed", "blue", 0, 0, 0, 0, 1),
                        NOW.plusSeconds(9), Duration.ofSeconds(5)).reason());
    }

    private static TenantDefinition policy(String id, int concurrent, long cpu, long memory,
                                           long backlog, long rate) {
        return new TenantDefinition(id, true, 1, concurrent, cpu, memory, backlog, rate, 0, NOW);
    }

    private static TenantAdmissionRequest request(String id, String tenant, int concurrent, long cpu,
                                                  long memory, long backlog, long triggers) {
        return new TenantAdmissionRequest(id, tenant, concurrent, cpu, memory, backlog, triggers);
    }
}
