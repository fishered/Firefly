package com.firefly.server;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutionRetryPolicy;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.governance.InMemoryTenantQuotaStore;
import com.firefly.governance.TenantAdmissionRequest;
import com.firefly.governance.TenantDefinition;
import com.firefly.governance.TenantQuotaAwareJobRepository;
import com.firefly.governance.TenantReservationKeys;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionMaintenanceWorkerTest {
    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void releasesTimedOutAttemptBeforeAdmittingItsRetry() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryTenantQuotaStore quotas = new InMemoryTenantQuotaStore();
        quotas.save(new TenantDefinition(
                "blue", true, 1, 1, 0, 0, 1, 0, 0, now
        ), 0, now);
        InMemoryJobRepository rawJobs = new InMemoryJobRepository(clock);
        var jobs = TenantQuotaAwareJobRepository.wrap(rawJobs, quotas, clock, new SchedulerMetrics());
        JobDefinition job = JobDefinition.builder()
                .id("timeout-job").name("Timeout job").handlerName("run")
                .schedule(new CronSchedule("0 * * * * *"))
                .retryPolicy(new ExecutionRetryPolicy(
                        2, Duration.ZERO, 1.0, Duration.ZERO, false, true
                ))
                .parameters(Map.of("firefly.resource.tenantId", "blue"))
                .build();
        assertTrue(jobs.enqueueManual(new ExecutionCommand("timeout-exec", job, now, now)));
        assertTrue(quotas.tryReserve(new TenantAdmissionRequest(
                TenantReservationKeys.running("timeout-exec"), "blue", 1, 0, 0, 0, 0
        ), now, Duration.ofMinutes(1)).admitted());

        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "timeout-exec", "timeout-exec", 0, job.id(), now, now,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                ExecutionStatus.RUNNING, 1, 1, "scheduler-a", 1,
                now.minusSeconds(1), now.minusSeconds(30), now.minusSeconds(30)
        ));
        ExecutionMaintenanceWorker worker = new ExecutionMaintenanceWorker(
                executions, clock,
                new ExecutionMaintenanceOptions(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofDays(1), 10, 10
                ),
                () -> true, jobs, quotas
        );

        worker.runOnce();

        assertEquals(0, quotas.usage("blue", now).activeExecutions());
        assertEquals(1, quotas.usage("blue", now).backlog());
        assertTrue(rawJobs.findDispatch("timeout-exec@attempt:1").isPresent());
        assertEquals(ExecutionStatus.TIMEOUT,
                executions.findExecution("timeout-exec").orElseThrow().status());
        worker.close();
    }
}
