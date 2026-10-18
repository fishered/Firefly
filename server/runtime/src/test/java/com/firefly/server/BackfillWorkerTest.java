package com.firefly.server;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.trigger.BackfillCoordinator;
import com.firefly.trigger.BackfillOptions;
import com.firefly.trigger.BackfillRequest;
import com.firefly.trigger.InMemoryBackfillOperationStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackfillWorkerTest {
    @Test
    void advancesBackfillsWithoutAdminPluginAndPublishesMetrics() {
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        jobs.save(JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), now.plusSeconds(60));
        BackfillCoordinator coordinator = new BackfillCoordinator(
                jobs, new InMemoryExecutionRepository(), new InMemoryBackfillOperationStore(), clock,
                "scheduler-a", Duration.ofSeconds(30)
        );
        coordinator.start(
                new BackfillRequest("run", "job", now, now.plusSeconds(120), 10, "root"),
                new BackfillOptions(10, 0, 100, Set.of())
        );
        SchedulerMetrics metrics = new SchedulerMetrics();
        BackfillWorker worker = new BackfillWorker(
                coordinator, metrics, clock, Duration.ofMillis(200), 8, 100
        );

        worker.tick();

        assertEquals(3, coordinator.progress("run").dispatched());
        assertEquals(0, metrics.snapshot().backfillActiveOperations());
        assertEquals(1, metrics.snapshot().backfillOperationsProcessed());
    }
}
