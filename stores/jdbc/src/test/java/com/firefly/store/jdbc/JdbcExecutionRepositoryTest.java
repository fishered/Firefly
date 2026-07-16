package com.firefly.store.jdbc;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExecutionRepositoryTest {
    @Test
    void serializesConcurrentTargetCompletionsThroughTheParentRow() throws Exception {
        JdbcExecutionRepository repository = new JdbcExecutionRepository(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-15T09:00:00Z");
        repository.saveExecution(new ExecutionRecord(
                "concurrent-exec", "job-1", now, now, ExecutorDispatchMode.BROADCAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                2, 2, "node-a", 3, now, now
        ));
        repository.saveTargets(List.of(
                target("concurrent-a", "concurrent-exec", "a", now),
                target("concurrent-b", "concurrent-exec", "b", now)
        ));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await();
                return repository.complete("concurrent-a", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(1));
            });
            var second = workers.submit(() -> {
                start.await();
                return repository.complete("concurrent-b", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(1));
            });
            start.countDown();
            assertTrue(first.get());
            assertTrue(second.get());
        }

        assertEquals(ExecutionStatus.SUCCEEDED,
                repository.findExecution("concurrent-exec").orElseThrow().status());
    }

    @Test
    void aggregatesBroadcastTargetResultsIntoTheParentExecution() {
        JdbcExecutionRepository repository = new JdbcExecutionRepository(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        repository.saveExecution(new ExecutionRecord(
                "exec-1", "job-1", now, now, ExecutorDispatchMode.BROADCAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                2, 2, "node-a", 7, now, now
        ));
        repository.saveTargets(List.of(
                target("exec-1@instance:a", "exec-1", "a", now),
                target("exec-1@instance:b", "exec-1", "b", now)
        ));

        assertTrue(repository.acknowledge("exec-1@instance:a", now.plusSeconds(1)));
        assertTrue(repository.complete("exec-1@instance:a", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(2)));
        assertTrue(repository.complete("exec-1@instance:b", ExecutionStatus.FAILED, "boom", now.plusSeconds(3)));

        assertEquals(ExecutionStatus.PARTIAL, repository.findExecution("exec-1").orElseThrow().status());
        assertEquals("boom", repository.listTargets("exec-1").get(1).errorMessage());
    }

    @Test
    void expiresExecutionsUsingTheImmutableAttemptDeadlineAfterJobDeletion() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        Instant now = Instant.parse("2026-07-14T10:00:10Z");
        java.util.concurrent.atomic.AtomicReference<Instant> databaseNow =
                new java.util.concurrent.atomic.AtomicReference<>(now.minusSeconds(10));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
        JdbcExecutionRepository repository = new JdbcExecutionRepository(dataSource, ignored -> databaseNow.get());
        jobs.save(com.firefly.domain.JobDefinition.builder()
                .id("timeout-job").name("Timeout job").handlerName("handler")
                .schedule(new com.firefly.domain.CronSchedule("0 * * * * *"))
                .timeout(java.time.Duration.ofSeconds(2)).build(), now);
        repository.startExecution(new ExecutionRecord(
                "timeout-exec", "timeout-job", now.minusSeconds(10), now.minusSeconds(10),
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                ExecutionStatus.RUNNING, 1, 1, "node-a", 1, now.minusSeconds(10), now.minusSeconds(10)
        ), java.time.Duration.ofSeconds(2));
        assertTrue(jobs.delete("timeout-job"));
        databaseNow.set(now);

        assertEquals(1, repository.expireTimedOut(now, 10));
        assertEquals(ExecutionStatus.TIMEOUT, repository.findExecution("timeout-exec").orElseThrow().status());
    }

    @Test
    void redispatchDoesNotRegressAnAcknowledgedTarget() {
        JdbcExecutionRepository repository = new JdbcExecutionRepository(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.saveExecution(new ExecutionRecord(
                "exec-retry", "job-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                1, 1, "node-a", 7, now, now
        ));
        repository.saveTargets(List.of(target("exec-retry", "exec-retry", "instance-a", now)));
        assertTrue(repository.acknowledge("exec-retry", now.plusSeconds(1)));

        repository.saveTargets(List.of(new ExecutionTargetRecord(
                "exec-retry", "exec-retry", "instance-b", "gateway-b", null,
                ExecutionStatus.DISPATCHED, 1, null, null, "", now, now.plusSeconds(2)
        )));

        ExecutionTargetRecord target = repository.listTargets("exec-retry").getFirst();
        assertEquals(ExecutionStatus.RUNNING, target.status());
        assertEquals("instance-a", target.instanceId());
        assertEquals(1, target.attempt());
    }

    @Test
    void terminalTargetAndParentStatesDoNotRegressOnLateMessages() {
        JdbcExecutionRepository repository = new JdbcExecutionRepository(JdbcTestSupport.dataSource());
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.saveExecution(new ExecutionRecord(
                "terminal-exec", "job-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                1, 1, "node-a", 7, now, now
        ));
        repository.saveTargets(List.of(target("terminal-exec", "terminal-exec", "instance-a", now)));

        assertTrue(repository.complete(
                "terminal-exec", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(1)
        ));
        assertEquals(com.firefly.execution.ExecutionMutationResult.ALREADY_APPLIED,
                repository.completeResult(
                        "terminal-exec", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(2)
                ));
        assertTrue(repository.acknowledge("terminal-exec", now.plusSeconds(2)));
        assertEquals(ExecutionStatus.SUCCEEDED,
                repository.findExecution("terminal-exec").orElseThrow().status());
        assertEquals(ExecutionStatus.SUCCEEDED, repository.listTargets("terminal-exec").getFirst().status());
        org.junit.jupiter.api.Assertions.assertFalse(repository.complete(
                "terminal-exec", ExecutionStatus.FAILED, "late failure", now.plusSeconds(3)
        ));
    }

    private ExecutionTargetRecord target(String targetId, String executionId, String instanceId, Instant now) {
        return new ExecutionTargetRecord(
                targetId, executionId, instanceId, "gateway-a", null,
                ExecutionStatus.DISPATCHED, 1, null, null, "", now, now
        );
    }
}
