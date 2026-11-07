package com.firefly.store;

import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryFairDispatchTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:10Z");

    @Test
    void claimsAWeightedFairBatchInsteadOfTheOldestTenantOnly() {
        InMemoryJobRepository repository = new InMemoryJobRepository(Clock.fixed(NOW, ZoneOffset.UTC));
        for (int index = 0; index < 20; index++) {
            enqueue(repository, "noisy-" + index, "noisy", 3, NOW.minusSeconds(5));
        }
        for (int index = 0; index < 20; index++) {
            enqueue(repository, "quiet-" + index, "quiet", 3, NOW.minusSeconds(1));
        }

        var claimed = repository.claimFairDispatches("gateway", NOW, 8, Duration.ofSeconds(5),
                Set.of(DispatchType.LOCAL),
                new DispatchFairnessPolicy(Map.of("noisy", 3, "quiet", 1), Duration.ofMinutes(1)));

        assertEquals(6, claimed.stream().filter(value -> tenant(value).equals("noisy")).count());
        assertEquals(2, claimed.stream().filter(value -> tenant(value).equals("quiet")).count());
    }

    @Test
    void priorityAgingPreventsAContinuousCriticalTenantFromStarvingBackgroundWork() {
        InMemoryJobRepository repository = new InMemoryJobRepository(Clock.fixed(NOW, ZoneOffset.UTC));
        enqueue(repository, "waiting", "quiet", 0, NOW.minusSeconds(9));
        for (int index = 0; index < 200; index++) {
            enqueue(repository, "critical-" + index, "noisy", 9, NOW);
        }

        var claimed = repository.claimFairDispatches("gateway", NOW, 101, Duration.ofSeconds(5),
                Set.of(DispatchType.LOCAL),
                new DispatchFairnessPolicy(Map.of("noisy", 100, "quiet", 1), Duration.ofSeconds(1)));

        assertTrue(claimed.stream().anyMatch(value -> value.outboxId().equals("waiting")));
    }

    private static void enqueue(InMemoryJobRepository repository, String id, String tenant,
                                int priority, Instant availableAt) {
        JobDefinition definition = JobDefinition.builder()
                .id("job-" + id).name(id).handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .parameters(Map.of(
                        "firefly.resource.tenantId", tenant,
                        "firefly.resource.priority", Integer.toString(priority)
                )).build();
        repository.enqueueManual(new ExecutionCommand(id, definition, availableAt, availableAt));
    }

    private static String tenant(DispatchOutboxRecord record) {
        return record.command().definition().tenantId();
    }
}
