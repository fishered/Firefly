package com.firefly.store.jdbc;

import com.firefly.audit.AuditRecord;
import com.firefly.audit.AuditQuery;
import com.firefly.executor.ExecutorInstanceLocation;
import com.firefly.executor.ExecutorResourceMetadata;
import com.firefly.executor.ExecutorResourceReport;
import com.firefly.executor.ExecutorResourceRequirement;
import com.firefly.store.JobHistoryRecord;
import com.firefly.store.JobHistoryQuery;
import com.firefly.trigger.EventTrigger;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionQuery;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperationalPersistenceTest {
    @Test
    void atomicallyReservesExecutorCapacityAcrossDirectoryClients() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        JdbcExecutorInstanceDirectory first = new JdbcExecutorInstanceDirectory(dataSource);
        JdbcExecutorInstanceDirectory second = new JdbcExecutorInstanceDirectory(dataSource);
        ExecutorInstanceLocation location = new ExecutorInstanceLocation(
                "orders", "instance-1", "gateway-a", "http://gateway-a:9801", "session-1",
                now, now.plusSeconds(90), ExecutorResourceMetadata.merge(
                        Map.of(), new ExecutorResourceReport(1_000, 1_024, Set.of("ssd"), 0, Map.of())
                )
        );
        first.register(location);
        ExecutorResourceRequirement requirement = new ExecutorResourceRequirement(
                "tenant-a", 100, 128, Set.of("ssd"), 0, 1
        );

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (int index = 0; index < 16; index++) {
                JdbcExecutorInstanceDirectory directory = index % 2 == 0 ? first : second;
                pool.submit(() -> {
                    start.await();
                    if (directory.tryReserve(location, requirement, now)) accepted.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
        }

        assertEquals(1, accepted.get());
        var report = ExecutorResourceMetadata.report(
                first.listOnline("orders", now).getFirst().metadata()
        ).orElseThrow();
        assertEquals(900, report.availableCpuMillis());
        assertEquals(1, report.tenantActiveExecutions().get("tenant-a"));
    }

    @Test
    void persistsAuditHistoryAndSharedExecutorLocations() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant now = Instant.parse("2026-07-19T10:00:00Z");

        JdbcAuditRepository audit = new JdbcAuditRepository(dataSource);
        audit.append(new AuditRecord(
                "audit-1", now, "ops", "ADMIN", "PATCH /api/jobs/job-1",
                "jobs", "job-1", "SUCCESS", "before", "after", "status=200"
        ));
        assertEquals("ops", audit.listRecent(10).getFirst().actor());

        JdbcJobHistoryRepository history = new JdbcJobHistoryRepository(dataSource);
        history.append(new JobHistoryRecord(
                "history-1", "job-1", 2, "SET_ENABLED", "ops",
                "before", "after", now
        ));
        assertEquals("SET_ENABLED", history.listByJob("job-1", 10).getFirst().action());

        JdbcExecutorInstanceDirectory directory = new JdbcExecutorInstanceDirectory(dataSource);
        directory.register(new ExecutorInstanceLocation(
                "orders", "instance-1", "gateway-a", "http://gateway-a:9801", "session-1",
                now, now.plusSeconds(35), Map.of("serviceName", "orders-service")
        ));
        assertEquals("gateway-a", directory.listOnline("orders", now).getFirst().gatewayNodeId());
        assertTrue(directory.heartbeat(
                "orders", "instance-1", "gateway-a", "session-1",
                now.plusSeconds(10), Duration.ofSeconds(35)
        ));
        assertTrue(directory.markOffline("orders", "instance-1", "gateway-a", "session-1"));
        assertTrue(directory.listOnline("orders", now.plusSeconds(11)).isEmpty());
    }

    @Test
    void persistsTriggerDeduplicationNoncesAndFailureRecovery() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        JdbcTriggerInbox first = new JdbcTriggerInbox(dataSource);
        JdbcTriggerInbox afterRestart = new JdbcTriggerInbox(dataSource);
        EventTrigger event = new EventTrigger(
                "event-1", "order.created", "key-1", "{}", now,
                EventTrigger.TriggerStatus.RECEIVED, null
        );

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (int index = 0; index < 16; index++) {
                JdbcTriggerInbox inbox = index % 2 == 0 ? first : afterRestart;
                pool.submit(() -> {
                    start.await();
                    if (inbox.receive("job-1", event)) accepted.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
        }
        assertEquals(1, accepted.get());
        assertEquals(false, new JdbcTriggerInbox(dataSource).receive("job-1", event));
        assertTrue(first.markFailed("key-1", now.plusSeconds(1), "gateway unavailable"));
        assertEquals("gateway unavailable", afterRestart.listFailed(10).getFirst().failureReason());
        assertTrue(first.reserveNonce("nonce-1", now.plusSeconds(600), now));
        assertEquals(false, afterRestart.reserveNonce("nonce-1", now.plusSeconds(600), now));
        assertTrue(afterRestart.claimFailedForReplay("key-1", now.plusSeconds(2)).isPresent());
        assertTrue(first.markProcessed("key-1", now.plusSeconds(3)));
        assertEquals(EventTrigger.TriggerStatus.PROCESSED,
                afterRestart.findByIdempotencyKey("key-1").orElseThrow().status());
    }

    @Test
    void keysetPagesExecutionsWithoutTimestampTieGaps() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcExecutionRepository repository = new JdbcExecutionRepository(dataSource);
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        for (int index = 0; index < 5; index++) {
            repository.saveExecution(new ExecutionRecord(
                    "execution-" + index, "root", index, "job", now, now,
                    ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                    ExecutionStatus.SUCCEEDED, 1, 1, "node", 1L, now, now
            ));
        }

        var first = repository.query(new ExecutionQuery("job", null, null, null, null, 2));
        var second = repository.query(new ExecutionQuery("job", null, null, null, first.nextCursor(), 2));
        var third = repository.query(new ExecutionQuery("job", null, null, null, second.nextCursor(), 2));

        assertEquals(Set.of("execution-4", "execution-3"), first.items().stream()
                .map(ExecutionRecord::executionId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("execution-2", "execution-1"), second.items().stream()
                .map(ExecutionRecord::executionId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("execution-0", third.items().getFirst().executionId());
        assertEquals(null, third.nextCursor());
    }

    @Test
    void keysetPagesAndFiltersAuditAndJobHistory() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        JdbcAuditRepository audit = new JdbcAuditRepository(dataSource);
        JdbcJobHistoryRepository history = new JdbcJobHistoryRepository(dataSource);
        for (int index = 0; index < 5; index++) {
            audit.append(new AuditRecord(
                    "audit-" + index, now, index == 0 ? "viewer" : "ops", "ADMIN",
                    "UPDATE", "jobs", "job-1", "SUCCESS", "before", "after", ""
            ));
            history.append(new JobHistoryRecord(
                    "history-" + index, index == 0 ? "job-2" : "job-1", index,
                    "UPDATE", "ops", "before", "after", now
            ));
        }

        var firstAudit = audit.query(new AuditQuery(
                "ops", "UPDATE", "jobs", "SUCCESS", null, null, null, 2
        ));
        var secondAudit = audit.query(new AuditQuery(
                "ops", "UPDATE", "jobs", "SUCCESS", null, null, firstAudit.nextCursor(), 2
        ));
        assertEquals(Set.of("audit-4", "audit-3"), firstAudit.items().stream()
                .map(AuditRecord::auditId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("audit-2", "audit-1"), secondAudit.items().stream()
                .map(AuditRecord::auditId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(null, secondAudit.nextCursor());

        var firstHistory = history.query(new JobHistoryQuery(
                "job-1", "UPDATE", null, null, null, 2
        ));
        var secondHistory = history.query(new JobHistoryQuery(
                "job-1", "UPDATE", null, null, firstHistory.nextCursor(), 2
        ));
        assertEquals(Set.of("history-4", "history-3"), firstHistory.items().stream()
                .map(JobHistoryRecord::historyId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("history-2", "history-1"), secondHistory.items().stream()
                .map(JobHistoryRecord::historyId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(null, secondHistory.nextCursor());
    }
}
