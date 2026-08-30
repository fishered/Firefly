package com.firefly.trigger;

import com.firefly.domain.*;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TriggerAndBackfillTest {
    private JobDefinition job() { return JobDefinition.builder().id("job").name("job").handlerName("h").schedule(new CronSchedule("0 * * * * *")).build(); }
    @Test void duplicateEventsProduceOneOutboxEntry() {
        InMemoryJobRepository jobs = new InMemoryJobRepository(); Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC); jobs.save(job(), clock.instant().plusSeconds(60));
        EventTriggerService service = new EventTriggerService(new InMemoryTriggerInbox(), jobs, clock);
        assertTrue(service.accept("job","e1","order","k1","{}").accepted());
        assertTrue(service.accept("job","e2","order","k1","{}").duplicate());
        assertEquals(1, jobs.outboxCounts().values().stream().mapToLong(Long::longValue).sum());
    }
    @Test void backfillIsBoundedAndIdempotent() {
        InMemoryJobRepository jobs = new InMemoryJobRepository(); Clock clock = Clock.systemUTC(); jobs.save(job(), clock.instant().plusSeconds(60));
        BackfillService service = new BackfillService(jobs, clock); Instant from = Instant.parse("2026-01-01T00:00:00Z");
        BackfillService.Result result = service.submit(new BackfillRequest("r","job",from,from.plusSeconds(120),10,"root"));
        assertEquals(3,result.expanded()); assertEquals(3,result.queued());
    }
}
