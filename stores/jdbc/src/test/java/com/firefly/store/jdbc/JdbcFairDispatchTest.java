package com.firefly.store.jdbc;

import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.DispatchFairnessPolicy;
import com.firefly.store.DispatchType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFairDispatchTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:10Z");

    @Test
    void selectsPerTenantCandidatesBeforeApplyingWeights() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcJobRepository repository = new JdbcJobRepository(dataSource, ignored -> NOW);
        for (int index = 0; index < 20; index++) {
            enqueue(repository, "noisy-" + index, "noisy", 3, NOW.minusSeconds(5));
        }
        for (int index = 0; index < 20; index++) {
            enqueue(repository, "quiet-" + index, "quiet", 3, NOW.minusSeconds(1));
        }

        var claimed = repository.claimFairDispatches("gateway", NOW, 8, Duration.ofSeconds(5),
                Set.of(DispatchType.LOCAL),
                new DispatchFairnessPolicy(Map.of("noisy", 3, "quiet", 1), Duration.ofMinutes(1)));

        assertEquals(6, claimed.stream().filter(value -> tenant(value.command().definition()).equals("noisy")).count());
        assertEquals(2, claimed.stream().filter(value -> tenant(value.command().definition()).equals("quiet")).count());
    }

    @Test
    void persistedPriorityAndAgingPreventStarvation() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        AtomicReference<Instant> databaseNow = new AtomicReference<>(NOW.minusSeconds(9));
        JdbcJobRepository repository = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        enqueue(repository, "waiting", "quiet", 0, NOW.minusSeconds(9));
        databaseNow.set(NOW);
        for (int index = 0; index < 200; index++) {
            enqueue(repository, "critical-" + index, "noisy", 9, NOW);
        }

        var claimed = repository.claimFairDispatches("gateway", NOW, 101, Duration.ofSeconds(5),
                Set.of(DispatchType.LOCAL),
                new DispatchFairnessPolicy(Map.of("noisy", 100, "quiet", 1), Duration.ofSeconds(1)));

        assertTrue(claimed.stream().anyMatch(value -> value.outboxId().equals("waiting")));
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void concurrentGatewaysNeverClaimTheSameFairQueueEntry() throws Exception {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcJobRepository first = new JdbcJobRepository(dataSource, ignored -> NOW);
        JdbcJobRepository second = new JdbcJobRepository(dataSource, ignored -> NOW);
        for (int index = 0; index < 40; index++) {
            enqueue(first, "entry-" + index, index % 2 == 0 ? "a" : "b", 3, NOW);
        }
        DispatchFairnessPolicy policy = new DispatchFairnessPolicy(
                Map.of("a", 1, "b", 1), Duration.ofMinutes(1));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return first.claimFairDispatches("gateway-a", NOW, 20, Duration.ofSeconds(5),
                        Set.of(DispatchType.LOCAL), policy);
            });
            var right = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return second.claimFairDispatches("gateway-b", NOW, 20, Duration.ofSeconds(5),
                        Set.of(DispatchType.LOCAL), policy);
            });
            start.countDown();
            var leftIds = left.get(10, TimeUnit.SECONDS).stream().map(value -> value.outboxId())
                    .collect(Collectors.toSet());
            var rightIds = right.get(10, TimeUnit.SECONDS).stream().map(value -> value.outboxId())
                    .collect(Collectors.toSet());
            assertEquals(20, leftIds.size());
            assertEquals(20, rightIds.size());
            assertTrue(java.util.Collections.disjoint(leftIds, rightIds));
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void fairnessStateSurvivesSingleItemBatchesAcrossGateways() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcJobRepository first = new JdbcJobRepository(dataSource, ignored -> NOW);
        JdbcJobRepository second = new JdbcJobRepository(dataSource, ignored -> NOW);
        for (int index = 0; index < 60; index++) {
            enqueue(first, "single-noisy-" + index, "noisy", 3, NOW);
            enqueue(first, "single-quiet-" + index, "quiet", 3, NOW);
        }
        DispatchFairnessPolicy policy = new DispatchFairnessPolicy(
                Map.of("noisy", 3, "quiet", 1), Duration.ofMinutes(1)
        );

        int noisy = 0;
        int quiet = 0;
        for (int index = 0; index < 40; index++) {
            JdbcJobRepository gateway = index % 2 == 0 ? first : second;
            var claimed = gateway.claimFairDispatches(
                    "gateway-" + index, NOW, 1, Duration.ofMinutes(5),
                    Set.of(DispatchType.LOCAL), policy
            );
            assertEquals(1, claimed.size());
            if (tenant(claimed.getFirst().command().definition()).equals("noisy")) noisy++;
            else quiet++;
        }

        assertEquals(30, noisy);
        assertEquals(10, quiet);
    }

    private static void enqueue(JdbcJobRepository repository, String id, String tenant,
                                int priority, Instant availableAt) {
        JobDefinition definition = JobDefinition.builder()
                .id("job-" + id).name(id).handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .parameters(Map.of(
                        "firefly.resource.tenantId", tenant,
                        "firefly.resource.priority", Integer.toString(priority)
                )).build();
        assertTrue(repository.enqueueManual(new ExecutionCommand(id, definition, availableAt, availableAt)));
    }

    private static String tenant(JobDefinition definition) {
        return definition.tenantId();
    }
}
