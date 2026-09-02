package com.firefly.execution;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void reportsDefinitionDifferencesAndRequiresConfirmation() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord("exec-1", "root-1", 0, "job", NOW, NOW,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.FAILED,
                1, 0, "node", 3, NOW, NOW));
        JobDefinition current = JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build();
        ReplayDefinitionSnapshot original = new ReplayDefinitionSnapshot(1, 2, 3, Map.of("mode", "old"));
        ReplayDefinitionSnapshot changed = new ReplayDefinitionSnapshot(2, 2, 3, Map.of("mode", "new"));
        ExecutionReplayPlan plan = new ExecutionReplayService(executions, Clock.fixed(NOW, ZoneOffset.UTC))
                .plan(new ExecutionReplayRequest("exec-1", current, original, changed, false, false, true, List.of("target-1")));

        assertTrue(plan.requiresConfirmation());
        assertEquals(List.of("definitionRevision", "parameter:mode"), plan.differences());
        assertFalse(plan.commandIfExecutable(false).isPresent());
        assertTrue(plan.commandIfExecutable(true).isPresent());
        assertEquals("root-1", plan.command().rootExecutionId());
        assertEquals("target-1", plan.command().definition().parameters().get(ExecutionReplayService.FAILED_TARGET_IDS_PARAMETER));
    }

    @Test
    void dryRunNeverProducesAnExecutableCommand() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord("exec-1", "root-1", 0, "job", NOW, NOW,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.FAILED,
                1, 0, "node", 3, NOW, NOW));
        JobDefinition current = JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build();
        ReplayDefinitionSnapshot snapshot = new ReplayDefinitionSnapshot(1, 0, 0, Map.of());
        ExecutionReplayPlan plan = new ExecutionReplayService(executions, Clock.fixed(NOW, ZoneOffset.UTC))
                .plan(ExecutionReplayRequest.dryRun("exec-1", current, snapshot, snapshot));
        assertTrue(plan.dryRun());
        assertFalse(plan.commandIfExecutable(true).isPresent());
    }
}
