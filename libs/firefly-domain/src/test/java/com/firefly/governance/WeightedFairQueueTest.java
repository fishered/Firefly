package com.firefly.governance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeightedFairQueueTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Test
    void distributesEqualPriorityWorkAccordingToTenantWeights() {
        Map<String, Integer> weights = Map.of("heavy", 3, "light", 1);
        WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofSeconds(10),
                tenant -> weights.getOrDefault(tenant, 1));
        for (int index = 0; index < 100; index++) {
            queue.offer(entry("h-" + index, "heavy", 3, NOW, "heavy"));
            queue.offer(entry("l-" + index, "light", 3, NOW, "light"));
        }

        Map<String, Integer> dispatched = new HashMap<>();
        for (int index = 0; index < 40; index++) {
            dispatched.merge(queue.poll(NOW).orElseThrow().value(), 1, Integer::sum);
        }

        assertEquals(30, dispatched.get("heavy"));
        assertEquals(10, dispatched.get("light"));
    }

    @Test
    void dispatchesHigherPriorityBeforeTenantWeightCanDominate() {
        WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofSeconds(10),
                tenant -> tenant.equals("bulk") ? 100 : 1);
        queue.offer(entry("bulk", "bulk", TenantPriority.STANDARD.level(), NOW, "bulk"));
        queue.offer(entry("urgent", "small", TenantPriority.CRITICAL.level(), NOW, "urgent"));

        assertEquals("urgent", queue.poll(NOW).orElseThrow().value());
    }

    @Test
    void agingMakesBackgroundWorkEligibleUnderContinuousCriticalLoad() {
        WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofSeconds(1),
                tenant -> tenant.equals("noisy") ? 100 : 1);
        queue.offer(entry("waiting", "quiet", TenantPriority.BACKGROUND.level(), NOW, "waiting"));
        for (int index = 0; index < 200; index++) {
            queue.offer(entry("critical-" + index, "noisy", TenantPriority.CRITICAL.level(), NOW,
                    "critical"));
        }

        boolean dispatched = false;
        Instant aged = NOW.plusSeconds(9);
        for (int poll = 0; poll < 101; poll++) {
            if (queue.poll(aged).orElseThrow().value().equals("waiting")) {
                dispatched = true;
                break;
            }
        }
        assertTrue(dispatched, "aged work must receive its weighted turn at the highest priority");
    }

    @Test
    void observesWeightChangesWithoutRebuildingTheQueue() {
        Map<String, Integer> weights = new HashMap<>(Map.of("a", 1, "b", 1));
        WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofMinutes(1),
                tenant -> weights.getOrDefault(tenant, 1));
        for (int index = 0; index < 20; index++) {
            queue.offer(entry("a-" + index, "a", 3, NOW, "a"));
            queue.offer(entry("b-" + index, "b", 3, NOW, "b"));
        }
        queue.poll(NOW);
        queue.poll(NOW);
        weights.put("a", 3);

        int a = 0;
        for (int index = 0; index < 16; index++) {
            if (queue.poll(NOW).orElseThrow().value().equals("a")) a++;
        }
        assertEquals(12, a);
    }

    @Test
    void rejectsDuplicateIdsAndDefaultsBlankTenant() {
        WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofSeconds(1), ignored -> 1);
        assertTrue(queue.offer(entry("same", "", 0, NOW, "first")));
        assertFalse(queue.offer(entry("same", "other", 9, NOW, "second")));
        assertEquals(TenantDefinition.DEFAULT_TENANT_ID, queue.poll(NOW).orElseThrow().tenantId());
        assertTrue(queue.isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void handlesHundredThousandEntryNoisyNeighborBacklogWithoutStarvation() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            WeightedFairQueue<String> queue = new WeightedFairQueue<>(Duration.ofSeconds(1),
                    tenant -> tenant.equals("noisy") ? 10 : 1);
            for (int index = 0; index < 90_000; index++) {
                queue.offer(entry("noisy-" + index, "noisy", TenantPriority.STANDARD.level(), NOW, "noisy"));
            }
            for (int index = 0; index < 10_000; index++) {
                queue.offer(entry("quiet-" + index, "quiet", TenantPriority.STANDARD.level(), NOW, "quiet"));
            }

            assertEquals(100_000, queue.size());
            int noisy = 0;
            int quiet = 0;
            for (int index = 0; index < 11_000; index++) {
                if (queue.poll(NOW).orElseThrow().value().equals("noisy")) noisy++;
                else quiet++;
            }
            assertEquals(10_000, noisy);
            assertEquals(1_000, quiet);
            assertEquals(89_000, queue.size());
        });
    }

    private static TenantQueueEntry<String> entry(String id, String tenant, int priority,
                                                   Instant enqueuedAt, String value) {
        return new TenantQueueEntry<>(id, tenant, priority, enqueuedAt, value);
    }
}
