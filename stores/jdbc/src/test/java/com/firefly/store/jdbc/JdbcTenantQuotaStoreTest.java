package com.firefly.store.jdbc;

import com.firefly.governance.TenantAdmissionReason;
import com.firefly.governance.TenantAdmissionRequest;
import com.firefly.governance.TenantDefinition;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTenantQuotaStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void enforcesQuotaAtomicallyAcrossGatewayInstances() throws Exception {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore first = new JdbcTenantQuotaStore(dataSource);
        JdbcTenantQuotaStore second = new JdbcTenantQuotaStore(dataSource);
        first.save(policy("blue", 3, 300, 3_000, 3, 100), 0, NOW);

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                int current = index;
                tasks.add(() -> (current % 2 == 0 ? first : second).tryReserve(
                        request("r-" + current, 1, 100, 1_000, 1, 1),
                        NOW, Duration.ofMinutes(1)).admitted());
            }
            List<Future<Boolean>> futures = executor.invokeAll(tasks);
            assertEquals(3, futures.stream().filter(value -> get(value)).count());
        }

        var usage = first.usage("blue", NOW);
        assertEquals(3, usage.activeExecutions());
        assertEquals(300, usage.reservedCpuMillis());
        assertEquals(3_000, usage.reservedMemoryBytes());
        assertEquals(3, usage.backlog());
    }

    @Test
    void supportsIdempotentReleaseLeaseRecoveryAndDynamicConfiguration() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(dataSource);
        TenantDefinition created = store.save(policy("blue", 1, 0, 0, 0, 1), 0, NOW);
        assertThrows(IllegalStateException.class, () -> store.save(policy("blue", 2, 0, 0, 0, 1), 0, NOW));

        var admitted = store.tryReserve(request("r1", 1, 0, 0, 0, 1),
                NOW, Duration.ofMillis(400));
        assertTrue(admitted.admitted());
        var replay = store.tryReserve(request("r1", 1, 0, 0, 0, 1),
                NOW.plusMillis(100), Duration.ofSeconds(5));
        assertEquals(TenantAdmissionReason.IDEMPOTENT_REPLAY, replay.reason());
        assertEquals(NOW.plusMillis(100).plusSeconds(5), replay.reservation().orElseThrow().expiresAt());
        assertEquals(TenantAdmissionReason.CONCURRENCY_QUOTA_EXCEEDED,
                store.tryReserve(request("r2", 1, 0, 0, 0, 0),
                        NOW, Duration.ofSeconds(5)).reason());

        assertEquals(0, store.reclaimExpired(NOW.plusMillis(500), 10));
        assertEquals(1, store.usage("blue", NOW.plusMillis(500)).activeExecutions());
        assertTrue(store.release("r1", NOW.plusMillis(500)));

        TenantDefinition expanded = new TenantDefinition("blue", true, 2, 2, 0, 0,
                0, 2, created.version(), NOW);
        TenantDefinition saved = store.save(expanded, created.version(), NOW.plusSeconds(1));
        assertEquals(2, saved.version());
        assertTrue(store.tryReserve(request("r2", 1, 0, 0, 0, 2),
                NOW.plusSeconds(1), Duration.ofSeconds(5)).admitted());
    }

    @Test
    void seedsDefaultTenantWithoutOverwritingOperatorChangesOnRestart() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(dataSource);
        TenantDefinition initial = store.find("").orElseThrow();
        TenantDefinition configured = new TenantDefinition("default", true, 7, 5,
                1_000, 2_000, 100, 10, initial.version(), NOW);
        store.save(configured, initial.version(), NOW);

        JdbcSchema.initialize(dataSource);

        TenantDefinition afterRestart = store.find("default").orElseThrow();
        assertEquals(7, afterRestart.weight());
        assertEquals(5, afterRestart.maxConcurrent());
        assertTrue(store.tryReserve(new TenantAdmissionRequest("legacy", "", 1, 0, 0, 0, 0),
                NOW, Duration.ofSeconds(5)).admitted());
    }

    @Test
    void clockSkewCannotResetTheClusterWideTriggerWindow() {
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(JdbcTestSupport.dataSource());
        store.save(policy("blue", 0, 0, 0, 0, 1), 0, NOW);
        assertTrue(store.tryReserve(request("newer", 0, 0, 0, 0, 1),
                NOW.plusSeconds(10), Duration.ofSeconds(5)).admitted());
        assertEquals(TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED,
                store.tryReserve(request("skewed", 0, 0, 0, 0, 1),
                        NOW.plusSeconds(9), Duration.ofSeconds(5)).reason());
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void concurrentConfigurationUpdatesUseCompareAndSetVersioning() throws Exception {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore first = new JdbcTenantQuotaStore(dataSource);
        JdbcTenantQuotaStore second = new JdbcTenantQuotaStore(dataSource);
        TenantDefinition initial = first.save(policy("blue", 1, 0, 0, 1, 1), 0, NOW);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> updates = List.of(
                    () -> updateAfter(start, first, initial, 2),
                    () -> updateAfter(start, second, initial, 3)
            );
            List<Future<Boolean>> futures = updates.stream().map(executor::submit).toList();
            start.countDown();
            assertEquals(1, futures.stream().filter(JdbcTenantQuotaStoreTest::get).count());
        }
        assertEquals(initial.version() + 1, first.find("blue").orElseThrow().version());
    }

    @Test
    void reconcilesBacklogReservationLeftByACrashedProducer() {
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(JdbcTestSupport.dataSource());
        store.save(policy("blue", 0, 0, 0, 2, 0), 0, NOW);
        assertTrue(store.tryReserve(request("orphan-execution", 0, 0, 0, 1, 0),
                NOW, Duration.ofDays(1)).admitted());

        assertEquals(0, store.reconcileBacklogReservations(NOW.plusSeconds(30), Duration.ofMinutes(1), 10));
        assertEquals(1, store.usage("blue", NOW.plusSeconds(30)).backlog());
        assertEquals(1, store.reconcileBacklogReservations(NOW.plusSeconds(61), Duration.ofMinutes(1), 10));
        assertEquals(0, store.usage("blue", NOW.plusSeconds(61)).backlog());
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void reconciliationKeepsActiveOutboxReservationsAndCleansTerminalOnes() throws Exception {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(dataSource);
        store.save(policy("blue", 0, 0, 0, 2, 0), 0, NOW);
        var job = com.firefly.domain.JobDefinition.builder()
                .id("job").name("job").handlerName("handler")
                .schedule(new com.firefly.domain.CronSchedule("0 * * * * *"))
                .parameters(java.util.Map.of("firefly.resource.tenantId", "blue"))
                .build();
        assertTrue(store.tryReserve(request("active-execution", 0, 0, 0, 1, 0),
                NOW, Duration.ofDays(1)).admitted());
        assertTrue(new JdbcJobRepository(dataSource).enqueueManual(
                new com.firefly.engine.ExecutionCommand("active-execution", job, NOW, NOW)
        ));

        assertEquals(0, store.reconcileBacklogReservations(NOW.plusSeconds(61), Duration.ofMinutes(1), 10));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "update firefly_dispatch_outbox set status='DONE' where outbox_id=?")) {
            statement.setString(1, "active-execution");
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(0, store.reconcileBacklogReservations(NOW.plusSeconds(62), Duration.ofMinutes(1), 10));
        assertEquals(1, store.usage("blue", NOW.plusSeconds(62)).backlog());

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "update firefly_execution set status='SUCCEEDED' where execution_id=?")) {
            statement.setString(1, "active-execution");
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(1, store.reconcileBacklogReservations(NOW.plusSeconds(63), Duration.ofMinutes(1), 10));
        assertEquals(0, store.usage("blue", NOW.plusSeconds(63)).backlog());
    }

    @Test
    void idempotentRenewalProtectsTerminalReservationDuringReplayGrace() throws Exception {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcTenantQuotaStore store = new JdbcTenantQuotaStore(dataSource);
        store.save(policy("blue", 0, 0, 0, 2, 0), 0, NOW);
        var job = com.firefly.domain.JobDefinition.builder()
                .id("job").name("job").handlerName("handler")
                .schedule(new com.firefly.domain.CronSchedule("0 * * * * *"))
                .parameters(java.util.Map.of("firefly.resource.tenantId", "blue"))
                .build();
        assertTrue(store.tryReserve(request("replay-execution", 0, 0, 0, 1, 0),
                NOW, Duration.ofDays(1)).admitted());
        assertTrue(new JdbcJobRepository(dataSource).enqueueManual(
                new com.firefly.engine.ExecutionCommand("replay-execution", job, NOW, NOW)
        ));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "update firefly_dispatch_outbox set status='DEAD' where outbox_id=?")) {
            statement.setString(1, "replay-execution");
            assertEquals(1, statement.executeUpdate());
        }
        Instant replayedAt = NOW.plusSeconds(120);
        assertEquals(com.firefly.governance.TenantAdmissionReason.IDEMPOTENT_REPLAY,
                store.tryReserve(request("replay-execution", 0, 0, 0, 1, 0),
                        replayedAt, Duration.ofDays(1)).reason());
        assertEquals(0, store.reconcileBacklogReservations(
                replayedAt.plusSeconds(1), Duration.ofMinutes(1), 10
        ));
        assertEquals(1, store.usage("blue", replayedAt.plusSeconds(1)).backlog());
        assertEquals(1, store.reconcileBacklogReservations(
                replayedAt.plusSeconds(61), Duration.ofMinutes(1), 10
        ));
        assertEquals(0, store.usage("blue", replayedAt.plusSeconds(61)).backlog());
    }

    private static boolean get(Future<Boolean> value) {
        try {
            return value.get();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean updateAfter(
            CountDownLatch start, JdbcTenantQuotaStore store, TenantDefinition initial, int weight
    ) throws Exception {
        start.await();
        try {
            store.save(new TenantDefinition(
                    initial.tenantId(), initial.enabled(), weight, initial.maxConcurrent(),
                    initial.maxCpuMillis(), initial.maxMemoryBytes(), initial.maxBacklog(),
                    initial.maxTriggerRatePerSecond(), initial.version(), NOW.plusSeconds(1)
            ), initial.version(), NOW.plusSeconds(1));
            return true;
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    private static TenantDefinition policy(String id, int concurrent, long cpu, long memory,
                                           long backlog, long rate) {
        return new TenantDefinition(id, true, 1, concurrent, cpu, memory, backlog, rate, 0, NOW);
    }

    private static TenantAdmissionRequest request(String id, int concurrent, long cpu,
                                                  long memory, long backlog, long triggers) {
        return new TenantAdmissionRequest(id, "blue", concurrent, cpu, memory, backlog, triggers);
    }
}
