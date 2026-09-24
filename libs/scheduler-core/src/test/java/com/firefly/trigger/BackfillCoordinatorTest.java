package com.firefly.trigger;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackfillCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void previewsPausesAndResumesFromCursorWithCanary() {
        InMemoryJobRepository jobs = jobs();
        BackfillCoordinator coordinator = new BackfillCoordinator(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
        BackfillRequest request = new BackfillRequest("run", "job", NOW, NOW.plusSeconds(180), 10, "root");
        BackfillOptions options = new BackfillOptions(2, 0, 50, Set.of());

        assertEquals(4, coordinator.preview(request, options).expanded());
        assertEquals(2, coordinator.start(request, options).remaining());
        assertEquals(BackfillProgress.BackfillStatus.PAUSED, coordinator.pause("run").status());
        assertEquals(0, coordinator.run("run", 2).dispatched());
        assertEquals(BackfillProgress.BackfillStatus.RUNNING, coordinator.resume("run").status());
        BackfillProgress canary = coordinator.run("run", 2);
        assertEquals(2, canary.dispatched());
        assertEquals(BackfillProgress.BackfillStatus.PAUSED, canary.status());
        assertEquals(2, coordinator.promote("run").remaining());
        assertEquals(4, coordinator.run("run", 2).dispatched());
        assertEquals(BackfillProgress.BackfillStatus.COMPLETED, coordinator.progress("run").status());
    }

    @Test
    void canSelectOnlyFailedFireTimes() {
        InMemoryJobRepository jobs = jobs();
        BackfillCoordinator coordinator = new BackfillCoordinator(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
        Instant failed = NOW.plusSeconds(120);
        BackfillOptions options = new BackfillOptions(10, 0, 100, Set.of(failed));
        BackfillPreview preview = coordinator.preview(
                new BackfillRequest("retry", "job", NOW, NOW.plusSeconds(180), 10, "retry-root"), options);
        assertEquals(java.util.List.of(failed), preview.fireTimes());
    }

    @Test
    void assignsEachFireTimeAnIndependentRootAndKeepsTheStartingDefinitionSnapshot() {
        InMemoryJobRepository jobs = jobs();
        BackfillCoordinator coordinator = new BackfillCoordinator(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
        BackfillRequest request = new BackfillRequest("run", "job", NOW, NOW.plusSeconds(60), 10, "operation");
        coordinator.start(request, new BackfillOptions(10, 0, 100, Set.of()));
        jobs.save(jobs.find("job").orElseThrow().definition().withParameters(Map.of("revision", "new")),
                NOW.plusSeconds(60));

        coordinator.run("run", 10);
        var commands = jobs.claimDispatches("test", NOW, 10, Duration.ofSeconds(1)).stream()
                .map(record -> record.command()).toList();
        assertEquals(2, commands.size());
        assertEquals(2, commands.stream().map(command -> command.rootExecutionId()).distinct().count());
        assertEquals(true, commands.stream().allMatch(command -> command.executionId().equals(command.rootExecutionId())));
        assertEquals(true, commands.stream().allMatch(command -> command.runAttempt() == 0));
        assertEquals(true, commands.stream().allMatch(command -> !command.definition().parameters().containsKey("revision")));
    }

    private static InMemoryJobRepository jobs() {
        InMemoryJobRepository jobs = new InMemoryJobRepository(Clock.fixed(NOW, ZoneOffset.UTC));
        jobs.save(JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), NOW.plusSeconds(60));
        return jobs;
    }
}
