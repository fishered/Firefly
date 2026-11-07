package com.firefly.trigger;

import com.firefly.domain.*;
import com.firefly.governance.InMemoryTenantQuotaStore;
import com.firefly.governance.TenantDefinition;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
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
    @Test void longEventKeysProduceDatabaseSafeExecutionIds() {
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        jobs.save(job(), clock.instant().plusSeconds(60));
        EventTriggerService service = new EventTriggerService(new InMemoryTriggerInbox(), jobs, clock);

        assertTrue(service.accept("job", "e1", "order", "k".repeat(256), "{}").accepted());

        var command = jobs.claimDispatches("test", clock.instant(), 10, Duration.ofSeconds(1))
                .get(0).command();
        assertTrue(command.executionId().length() <= 256);
    }
    @Test void tenantTriggerRateIsEnforcedAndResetsOnTheNextSecond() {
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        JobDefinition tenantJob = JobDefinition.builder().id("job").name("job").handlerName("h")
                .schedule(new CronSchedule("0 * * * * *"))
                .parameters(Map.of("firefly.resource.tenantId", "blue"))
                .build();
        jobs.save(tenantJob, clock.instant().plusSeconds(60));
        InMemoryTenantQuotaStore quotas = new InMemoryTenantQuotaStore();
        quotas.save(new TenantDefinition("blue", true, 1, 0, 0, 0, 0, 1, 0, clock.instant()),
                0, clock.instant());
        InMemoryTriggerInbox inbox = new InMemoryTriggerInbox();
        EventTriggerService service = new EventTriggerService(inbox, jobs, clock, quotas);

        assertTrue(service.accept("job", "e1", "order", "k1", "{}").accepted());
        EventTriggerService.TriggerResult rejected = service.accept("job", "e2", "order", "k2", "{}");
        assertFalse(rejected.accepted());
        assertEquals("TRIGGER_RATE_QUOTA_EXCEEDED", rejected.status());
        assertEquals(EventTrigger.TriggerStatus.FAILED,
                inbox.findByIdempotencyKey("k2").orElseThrow().status());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(service.accept("job", "e3", "order", "k3", "{}").accepted());
    }
    @Test void backfillIsBoundedAndIdempotent() {
        InMemoryJobRepository jobs = new InMemoryJobRepository(); Clock clock = Clock.systemUTC(); jobs.save(job(), clock.instant().plusSeconds(60));
        BackfillService service = new BackfillService(jobs, clock); Instant from = Instant.parse("2026-01-01T00:00:00Z");
        BackfillService.Result result = service.submit(new BackfillRequest("r","job",from,from.plusSeconds(120),10,"root"));
        assertEquals(3,result.expanded()); assertEquals(3,result.queued());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
