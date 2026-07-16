package com.firefly.store.jdbc;

import com.firefly.cluster.ShardHasher;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.DispatchType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDispatchOutboxTest {
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
        databaseNow.set(now.plusSeconds(5));
        assertTrue(jobs.claimDispatches("node-b", now.plusSeconds(5), 10, Duration.ofSeconds(15)).isEmpty());
        databaseNow.set(now.plusSeconds(11));
        assertEquals(1, jobs.claimDispatches("node-b", now.plusSeconds(11), 10, Duration.ofSeconds(15)).size());
        assertTrue(jobs.markDispatchSent(command.executionId(), now.plusSeconds(20)));
        assertTrue(jobs.acknowledgeDispatch(command.executionId(), now.plusSeconds(12)));
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
}
