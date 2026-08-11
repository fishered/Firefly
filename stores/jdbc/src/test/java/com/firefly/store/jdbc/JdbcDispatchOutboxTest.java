package com.firefly.store.jdbc;

import com.firefly.cluster.ShardHasher;
import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import com.firefly.store.SchedulingAdvance;
import com.firefly.tracing.TraceCarrier;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcDispatchOutboxTest {
    @Test
    void persistsTraceContextAcrossOutboxClaim() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.parse("2026-08-11T08:00:00Z");
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> now);
        ExecutionCommand command = new ExecutionCommand(
                "traced-execution", remoteJob("traced-job"), now, now
        ).withTraceCarrier(new TraceCarrier(Map.of(
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "tracestate", "vendor=value"
        )));

        assertTrue(jobs.enqueueManual(command));
        var claimed = jobs.claimDispatches(
                "gateway-a", now, 1, Duration.ofSeconds(15), Set.of(DispatchType.REMOTE)
        ).getFirst();

        assertEquals(command.traceCarrier(), claimed.command().traceCarrier());
    }

    @Test
    void batchAdvanceEnqueuesOnlySuccessfulCursorUpdates() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant fireTime = Instant.parse("2026-07-18T08:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(fireTime);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
        JdbcShardManager shards = new JdbcShardManager(dataSource, ignored -> databaseNow.get());
        List<JobDefinition> definitions = List.of(
                remoteJob("batch-job-a"), remoteJob("batch-job-b"), remoteJob("batch-job-c")
        );
        definitions.forEach(job -> jobs.save(job, fireTime));

        List<SchedulingAdvance> advances = definitions.stream().map(job -> {
            int shardId = ShardHasher.shardFor(job.id(), 32);
            var lease = shards.acquire(shardId, "node-a", fireTime, Duration.ofMinutes(5))
                    .orElseThrow();
            Instant expected = job.id().equals("batch-job-b") ? fireTime.minusSeconds(1) : fireTime;
            ExecutionCommand command = new ExecutionCommand(
                    job.id() + "@" + fireTime, job, fireTime, fireTime,
                    "node-a", lease.fencingToken()
            );
            return new SchedulingAdvance(job.id(), expected, fireTime.plusSeconds(60), List.of(command));
        }).toList();

        assertEquals(List.of(true, false, true), jobs.advanceAndEnqueueBatch(advances));
        assertTrue(executions.findExecution("batch-job-a@" + fireTime).isPresent());
        assertTrue(executions.findExecution("batch-job-b@" + fireTime).isEmpty());
        assertTrue(executions.findExecution("batch-job-c@" + fireTime).isPresent());
        assertEquals(2L, jobs.outboxCounts().get(DispatchOutboxStatus.PENDING));
    }

    @Test
    void batchAdvancePreservesForbidConcurrencyPolicy() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant fireTime = Instant.parse("2026-07-18T08:30:00Z");
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> fireTime);
        JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
        JdbcShardManager shards = new JdbcShardManager(dataSource, ignored -> fireTime);
        List<JobDefinition> definitions = List.of("forbid-job-a", "forbid-job-b").stream()
                .map(id -> JobDefinition.builder()
                        .id(id).name(id).handlerName("remote:orders:run")
                        .schedule(new CronSchedule("0 * * * * *"))
                        .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                        .build())
                .toList();
        definitions.forEach(job -> jobs.save(job, fireTime));
        assertTrue(jobs.enqueueManual(new ExecutionCommand(
                "active-forbid-execution", definitions.getFirst(), fireTime, fireTime
        )));

        List<SchedulingAdvance> advances = definitions.stream().map(job -> {
            int shardId = ShardHasher.shardFor(job.id(), 32);
            var lease = shards.acquire(shardId, "node-a", fireTime, Duration.ofMinutes(5)).orElseThrow();
            ExecutionCommand command = new ExecutionCommand(
                    job.id() + "@" + fireTime, job, fireTime, fireTime,
                    "node-a", lease.fencingToken()
            );
            return new SchedulingAdvance(
                    job.id(), fireTime, fireTime.plusSeconds(60), List.of(command)
            );
        }).toList();

        assertEquals(List.of(true, true), jobs.advanceAndEnqueueBatch(advances));
        assertTrue(executions.findExecution("forbid-job-a@" + fireTime).isEmpty());
        assertTrue(executions.findExecution("forbid-job-b@" + fireTime).isPresent());
        assertEquals(2L, jobs.outboxCounts().get(DispatchOutboxStatus.PENDING));
    }

    @Test
    void batchAdvanceRollsBackAllCursorsWhenEnqueueFails() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant fireTime = Instant.parse("2026-07-18T09:00:00Z");
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> fireTime);
        JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
        JdbcShardManager shards = new JdbcShardManager(dataSource, ignored -> fireTime);
        List<JobDefinition> definitions = List.of(remoteJob("rollback-job-a"), remoteJob("rollback-job-b"));
        definitions.forEach(job -> jobs.save(job, fireTime));
        List<SchedulingAdvance> advances = definitions.stream().map(job -> {
            int shardId = ShardHasher.shardFor(job.id(), 32);
            var lease = shards.acquire(shardId, "node-a", fireTime, Duration.ofMinutes(5))
                    .orElseThrow();
            ExecutionCommand duplicate = new ExecutionCommand(
                    "duplicate-execution", job, fireTime, fireTime, "node-a", lease.fencingToken()
            );
            return new SchedulingAdvance(
                    job.id(), fireTime, fireTime.plusSeconds(60), List.of(duplicate)
            );
        }).toList();

        assertThrows(JdbcException.class, () -> jobs.advanceAndEnqueueBatch(advances));
        definitions.forEach(job -> assertEquals(
                fireTime, jobs.find(job.id()).orElseThrow().nextFireTime()
        ));
        assertTrue(executions.findExecution("duplicate-execution").isEmpty());
    }

    private JobDefinition remoteJob(String id) {
        return JobDefinition.builder()
                .id(id).name(id).handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *"))
                .concurrencyPolicy(ConcurrencyPolicy.ALLOW)
                .build();
    }

    @Test
    void deferredDispatchRemainsClaimableWhenAnExecutorReturnsBeforeTheDeadline() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.parse("2026-07-18T08:00:00Z");
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JobDefinition job = JobDefinition.builder()
                .id("recovering-job").name("Recovering job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *"))
                .timeout(Duration.ofSeconds(10)).build();
        ExecutionCommand command = new ExecutionCommand(
                "recovering-exec", job, now, now, "node-a", 1L
        );
        assertTrue(jobs.enqueueManual(command));

        var firstClaim = jobs.claimDispatches(
                "gateway-without-route", now, 1, Duration.ofSeconds(2)
        ).getFirst();
        assertTrue(jobs.deferClaimedDispatch(
                command.executionId(), "gateway-without-route", firstClaim.attempt(),
                Duration.ofSeconds(1), "no executor route"
        ));

        databaseNow.set(now.plusSeconds(1));
        var recoveredClaim = jobs.claimDispatches(
                "gateway-with-route", databaseNow.get(), 1, Duration.ofSeconds(2)
        ).getFirst();
        assertEquals(1, recoveredClaim.attempt());
        assertTrue(jobs.markClaimedDispatchSentFor(
                command.executionId(), "gateway-with-route", recoveredClaim.attempt(),
                Duration.ofSeconds(2)
        ));
        assertEquals(1L, jobs.outboxCounts().get(DispatchOutboxStatus.SENT));
    }

    @Test
    void atomicallyAdvancesTheCursorAndRedeliversUntilAcknowledged() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
        JdbcShardManager shards = new JdbcShardManager(dataSource, ignored -> databaseNow.get());
        Instant first = now.plusSeconds(5);
        Instant next = first.plusSeconds(60);
        JobDefinition job = JobDefinition.builder()
                .id("outbox-job").name("Outbox job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        jobs.save(job, first);
        int shardId = ShardHasher.shardFor(job.id(), 32);
        var lease = shards.acquire(shardId, "node-a", now, Duration.ofMinutes(5)).orElseThrow();
        ExecutionCommand command = new ExecutionCommand(
                "outbox-job@" + first, job, first, now, "node-a", lease.fencingToken()
        );

        assertFalse(jobs.advanceAndEnqueue(job.id(), first, next, List.of(command)));
        databaseNow.set(first);
        assertTrue(jobs.advanceAndEnqueue(job.id(), first, next, List.of(command)));
        assertEquals(next, jobs.find(job.id()).orElseThrow().nextFireTime());
        assertTrue(executions.findExecution(command.executionId()).isPresent());

        var claimed = jobs.claimDispatches("node-a", now, 10, Duration.ofSeconds(15));
        assertEquals(1, claimed.size());
        assertTrue(jobs.markDispatchSent(command.executionId(), now.plusSeconds(10)));
        assertEquals(1L, jobs.countActiveDispatchesOwnedBy("node-a"));
        databaseNow.set(now.plusSeconds(5));
        assertTrue(jobs.claimDispatches("node-b", now.plusSeconds(5), 10, Duration.ofSeconds(15)).isEmpty());
        databaseNow.set(now.plusSeconds(11));
        assertEquals(1, jobs.claimDispatches("node-b", now.plusSeconds(11), 10, Duration.ofSeconds(15)).size());
        assertTrue(jobs.markDispatchSent(command.executionId(), now.plusSeconds(20)));
        assertEquals(0L, jobs.countActiveDispatchesOwnedBy("node-a"));
        assertEquals(1L, jobs.countActiveDispatchesOwnedBy("node-b"));
        assertTrue(jobs.acknowledgeDispatch(command.executionId(), now.plusSeconds(12)));
        assertEquals(0L, jobs.countActiveDispatchesOwnedBy("node-b"));
        assertTrue(jobs.claimDispatches("node-a", now.plusSeconds(30), 10, Duration.ofSeconds(15)).isEmpty());

        Instant third = next.plusSeconds(60);
        ExecutionCommand overlapping = new ExecutionCommand(
                "outbox-job@" + next, job, next, now.plusSeconds(1), "node-a", lease.fencingToken()
        );
        databaseNow.set(next);
        assertTrue(jobs.advanceAndEnqueue(job.id(), next, third, List.of(overlapping)));
        assertTrue(executions.findExecution(overlapping.executionId()).isEmpty());

        assertFalse(jobs.advanceAndEnqueue(job.id(), first, next.plusSeconds(60), List.of(command)));
    }

    @Test
    void claimsByRoleAndUsesTheImmutableJobSnapshotAfterDefinitionDeletion() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JdbcShardManager shards = new JdbcShardManager(dataSource, ignored -> databaseNow.get());
        Instant fireTime = now.plusSeconds(5);
        JobDefinition job = JobDefinition.builder()
                .id("snapshot-job").name("Snapshot job").handlerName("remote:orders:original")
                .schedule(new CronSchedule("0 * * * * *")).build();
        jobs.save(job, fireTime);
        int shardId = ShardHasher.shardFor(job.id(), 32);
        var lease = shards.acquire(shardId, "node-a", now, Duration.ofMinutes(5)).orElseThrow();
        ExecutionCommand command = new ExecutionCommand(
                "snapshot-job@" + fireTime, job, fireTime, now, "node-a", lease.fencingToken()
        );

        databaseNow.set(fireTime);
        assertTrue(jobs.advanceAndEnqueue(job.id(), fireTime, fireTime.plusSeconds(60), List.of(command)));
        assertTrue(jobs.delete(job.id()));
        assertTrue(jobs.claimDispatches(
                "scheduler-a", now, 10, Duration.ofSeconds(15), Set.of(DispatchType.LOCAL)
        ).isEmpty());

        var claimed = jobs.claimDispatches(
                "gateway-a", now, 10, Duration.ofSeconds(15), Set.of(DispatchType.REMOTE)
        );
        assertEquals(1, claimed.size());
        assertEquals("remote:orders:original", claimed.getFirst().command().definition().handlerName());
    }

    @Test
    void schedulesOnlyOneDelayedBusinessRetryPerFailedAttempt() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
        JobDefinition job = JobDefinition.builder()
                .id("retry-job").name("Retry job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *"))
                .retryPolicy(new com.firefly.domain.ExecutionRetryPolicy(
                        3, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(30), true, true
                )).build();
        ExecutionCommand command = new ExecutionCommand("retry-exec", job, now, now);
        assertTrue(jobs.enqueueManual(command));
        executions.saveExecution(new com.firefly.execution.ExecutionRecord(
                "retry-exec", "retry-exec", 0, job.id(), now, now,
                job.dispatchMode(), job.completionPolicy(), com.firefly.execution.ExecutionStatus.FAILED,
                1, 0, "local", 1, now, now
        ));

        assertTrue(jobs.scheduleExecutionRetry("retry-exec", now, false));
        assertFalse(jobs.scheduleExecutionRetry("retry-exec", now, false));
        databaseNow.set(now.plusSeconds(5));
        var retry = jobs.claimDispatches("scheduler", now, 10, Duration.ofSeconds(15)).stream()
                .filter(record -> record.outboxId().contains("@attempt:"))
                .findFirst().orElseThrow();
        assertEquals("retry-exec", retry.command().rootExecutionId());
        assertEquals(1, retry.command().runAttempt());
        assertEquals(now.plusSeconds(5).plus(job.timeout()),
                executions.findExecution(retry.command().executionId()).orElseThrow().timeoutAt());
    }

    @Test
    void listsAndRequeuesDeadDispatches() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> now);
        JobDefinition job = JobDefinition.builder()
                .id("dead-job").name("Dead job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        ExecutionCommand command = new ExecutionCommand("dead-exec", job, now, now, "node-a", 1L);

        assertTrue(jobs.enqueueManual(command));
        assertEquals(1, jobs.claimDispatches("gateway-a", now, 10, Duration.ofSeconds(15)).size());
        assertTrue(jobs.retryDispatch("dead-exec", now.plusSeconds(30), "gateway unavailable", 0));

        var dead = jobs.listDeadDispatches(10);
        assertEquals(1, dead.size());
        assertEquals("dead-exec", dead.getFirst().outboxId());
        assertEquals("dead-exec", dead.getFirst().command().executionId());
        assertEquals("gateway unavailable", dead.getFirst().lastError());
        assertEquals(0, jobs.claimDispatches("gateway-a", now, 10, Duration.ofSeconds(15)).size());

        assertTrue(jobs.requeueDeadDispatch("dead-exec", now));
        assertFalse(jobs.requeueDeadDispatch("dead-exec", now));
        assertTrue(jobs.listDeadDispatches(10).isEmpty());
        assertEquals(1, jobs.claimDispatches("gateway-a", now, 10, Duration.ofSeconds(15)).size());
    }

    @Test
    void fencesLateOutboxWritesAfterAnotherGatewayReclaimsTheDispatch() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicReference<Instant> databaseNow = new AtomicReference<>(now);
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, ignored -> databaseNow.get());
        JobDefinition job = JobDefinition.builder()
                .id("claim-fence-job").name("Claim fence job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        ExecutionCommand command = new ExecutionCommand("claim-fence-exec", job, now, now, "node-a", 1L);
        assertTrue(jobs.enqueueManual(command));

        var first = jobs.claimDispatches("gateway-a", now, 1, Duration.ofSeconds(5)).getFirst();
        databaseNow.set(now.plusSeconds(6));
        var second = jobs.claimDispatches("gateway-b", now, 1, Duration.ofSeconds(5)).getFirst();
        assertEquals(first.attempt() + 1, second.attempt());
        assertEquals("gateway-b", second.claimOwner());

        assertFalse(jobs.markClaimedDispatchSentFor(
                command.executionId(), "gateway-a", first.attempt(), Duration.ofSeconds(10)
        ));
        assertFalse(jobs.retryClaimedDispatchAfter(
                command.executionId(), "gateway-a", first.attempt(), Duration.ofSeconds(1),
                "late failure", 5
        ));

        assertTrue(jobs.acknowledgeDispatch(command.executionId(), now.plusSeconds(6)));
        assertFalse(jobs.retryClaimedDispatchAfter(
                command.executionId(), "gateway-b", second.attempt(), Duration.ofSeconds(1),
                "late failure after ack", 5
        ));
        assertEquals(1L, jobs.outboxCounts().get(DispatchOutboxStatus.DONE));
        assertTrue(jobs.claimDispatches("gateway-c", now.plusSeconds(30), 1, Duration.ofSeconds(5)).isEmpty());
    }
}
