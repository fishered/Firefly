package com.firefly.trigger;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCoalescingServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void coalescesBurstAndKeepsLatestPayload() {
        MutableClock clock = new MutableClock(NOW);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        jobs.save(JobDefinition.builder().id("orders").name("orders").handlerName("orders-handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), NOW.plusSeconds(60));
        InMemoryTriggerInbox inbox = new InMemoryTriggerInbox();
        EventCoalescingService service = new EventCoalescingService(inbox, jobs, clock, new EventCoalescer());
        EventAggregationPolicy policy = new EventAggregationPolicy("customer-42", Duration.ofMillis(500), Duration.ofSeconds(5));

        assertTrue(service.accept("orders", "e1", "order", "k1", "one", policy).accepted());
        assertTrue(service.accept("orders", "e2", "order", "k2", "two", policy).accepted());
        assertEquals(0, jobs.outboxCounts().values().stream().mapToLong(Long::longValue).sum());

        clock.advance(Duration.ofMillis(500));
        assertEquals(new EventCoalescingService.FlushResult(1, 1), service.flushDue());
        var command = jobs.claimDispatches("test", clock.instant(), 10, Duration.ofSeconds(1))
                .stream().map(record -> record.command())
                .filter(value -> value.executionId().equals("orders@event:customer-42:2026-09-02T00:00:00Z"))
                .findFirst().orElseThrow();
        assertEquals("two", command.definition().parameters().get(EventCoalescingService.LATEST_PAYLOAD_PARAMETER));
        assertEquals("2", command.definition().parameters().get(EventCoalescingService.EVENT_COUNT_PARAMETER));
        assertEquals(EventTrigger.TriggerStatus.PROCESSED, inbox.findByIdempotencyKey("k1").orElseThrow().status());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
