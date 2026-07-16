package com.firefly.execution;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryExecutionRepositoryTest {
    @Test
    void distinguishesInitialTransitionsFromIdempotentReplays() {
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        InMemoryExecutionRepository repository = new InMemoryExecutionRepository();
        repository.saveExecution(new ExecutionRecord(
                "execution-1", "job-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                1, 1, "node-a", 7, now, now
        ));
        repository.saveTargets(List.of(new ExecutionTargetRecord(
                "execution-1", "execution-1", "instance-a", "gateway-a", null,
                ExecutionStatus.DISPATCHED, 1, null, null, "", now, now
        )));

        assertEquals(ExecutionMutationResult.APPLIED,
                repository.acknowledgeResult("execution-1", now.plusSeconds(1)));
        assertEquals(ExecutionMutationResult.ALREADY_APPLIED,
                repository.acknowledgeResult("execution-1", now.plusSeconds(2)));
        assertEquals(ExecutionMutationResult.APPLIED,
                repository.completeResult("execution-1", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(3)));
        assertEquals(ExecutionMutationResult.ALREADY_APPLIED,
                repository.completeResult("execution-1", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(4)));
        assertEquals(ExecutionMutationResult.REJECTED,
                repository.completeResult("execution-1", ExecutionStatus.FAILED, "late", now.plusSeconds(5)));
    }

    @Test
    void doesNotCompleteParentBeforeEveryExpectedTargetExists() {
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        InMemoryExecutionRepository repository = new InMemoryExecutionRepository();
        repository.saveExecution(new ExecutionRecord(
                "execution-2", "job-1", now, now, ExecutorDispatchMode.SHARDING,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                2, 1, "node-a", 7, now, now
        ));
        repository.saveTargets(List.of(new ExecutionTargetRecord(
                "execution-2@shard:0", "execution-2", "instance-a", "gateway-a", 0,
                ExecutionStatus.DISPATCHED, 1, null, null, "", now, now
        )));

        repository.completeResult(
                "execution-2@shard:0", ExecutionStatus.SUCCEEDED, "", now.plusSeconds(1)
        );

        assertEquals(ExecutionStatus.DISPATCHED,
                repository.findExecution("execution-2").orElseThrow().status());
    }
}
