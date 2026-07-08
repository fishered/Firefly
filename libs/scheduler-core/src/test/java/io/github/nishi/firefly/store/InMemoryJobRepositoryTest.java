package io.github.nishi.firefly.store;

import io.github.nishi.firefly.domain.FixedRateSchedule;
import io.github.nishi.firefly.domain.JobDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryJobRepositoryTest {
    @Test
    void findsDueJobsInFireTimeOrder() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        JobDefinition later = job("later");
        JobDefinition earlier = job("earlier");

        repository.save(later, Instant.parse("2026-07-06T00:00:20Z"));
        repository.save(earlier, Instant.parse("2026-07-06T00:00:10Z"));

        var due = repository.findDue(Instant.parse("2026-07-06T00:00:30Z"), 10);

        assertEquals("earlier", due.get(0).definition().id());
        assertEquals("later", due.get(1).definition().id());
    }

    @Test
    void dueBatchDoesNotSplitSameFireTimeBySoftLimit() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        Instant fireTime = Instant.parse("2026-07-06T00:00:10Z");

        for (int i = 0; i < 150; i++) {
            repository.save(job("job-" + i), fireTime);
        }
        repository.save(job("later"), Instant.parse("2026-07-06T00:00:20Z"));

        DueJobBatch batch = repository.findDueBatch(Instant.parse("2026-07-06T00:00:30Z"), 100, 1_000);

        assertEquals(fireTime, batch.fireTime());
        assertEquals(150, batch.records().size());
        assertFalse(batch.truncated());
        assertTrue(batch.records().stream().allMatch(record -> record.nextFireTime().equals(fireTime)));
    }

    @Test
    void dueBatchReportsHardLimitTruncation() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        Instant fireTime = Instant.parse("2026-07-06T00:00:10Z");

        for (int i = 0; i < 5; i++) {
            repository.save(job("job-" + i), fireTime);
        }

        DueJobBatch batch = repository.findDueBatch(Instant.parse("2026-07-06T00:00:30Z"), 2, 3);

        assertEquals(3, batch.records().size());
        assertTrue(batch.truncated());
    }

    @Test
    void ignoresDisabledJobs() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        JobDefinition disabled = JobDefinition.builder()
                .id("disabled")
                .name("Disabled")
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(10)))
                .enabled(false)
                .build();

        repository.save(disabled, Instant.parse("2026-07-06T00:00:00Z"));

        assertTrue(repository.findDue(Instant.parse("2026-07-06T00:00:30Z"), 10).isEmpty());
    }

    @Test
    void updatesNextFireTimeOnlyWhenExpectedValueMatches() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        JobDefinition job = job("job-a");
        Instant first = Instant.parse("2026-07-06T00:00:00Z");
        Instant second = Instant.parse("2026-07-06T00:00:10Z");
        Instant third = Instant.parse("2026-07-06T00:00:20Z");

        repository.save(job, first);

        assertFalse(repository.updateNextFireTime("job-a", third, second));
        assertTrue(repository.updateNextFireTime("job-a", first, second));
        assertEquals(second, repository.find("job-a").orElseThrow().nextFireTime());
    }

    @Test
    void replacesExistingJobWithoutLeavingOldFireTimeInIndex() {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        JobDefinition job = job("job-a");

        repository.save(job, Instant.parse("2026-07-06T00:00:00Z"));
        repository.save(job, Instant.parse("2026-07-06T00:10:00Z"));

        assertTrue(repository.findDue(Instant.parse("2026-07-06T00:00:30Z"), 10).isEmpty());
        assertEquals(List.of("job-a"), repository.findDue(Instant.parse("2026-07-06T00:10:00Z"), 10).stream()
                .map(record -> record.definition().id())
                .toList());
    }

    private static JobDefinition job(String id) {
        return JobDefinition.builder()
                .id(id)
                .name(id)
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(10)))
                .build();
    }
}

