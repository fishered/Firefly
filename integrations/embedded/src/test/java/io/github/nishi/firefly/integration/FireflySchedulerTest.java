package io.github.nishi.firefly.integration;

import io.github.nishi.firefly.domain.CronSchedule;
import io.github.nishi.firefly.domain.JobDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FireflySchedulerTest {
    @Test
    void registersJobAndHandlerForTraditionalServices() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneId.of("UTC"));
        FireflyOptions options = FireflyOptions.builder()
                .clock(clock)
                .workerThreads(1)
                .build();

        try (FireflyScheduler scheduler = FireflyScheduler.create(options)) {
            AtomicInteger counter = new AtomicInteger();
            JobDefinition definition = JobDefinition.builder()
                    .id("demo")
                    .name("Demo")
                    .handlerName("demoHandler")
                    .schedule(new CronSchedule("*/5 * * * * *"))
                    .zoneId(ZoneId.of("UTC"))
                    .build();

            scheduler.register(FireflyJobRegistration.of(definition, ignored -> counter.incrementAndGet()));

            assertEquals("demoHandler", definition.handlerName());
            assertEquals("embedded", scheduler.catalog().findJobGroup("default").orElseThrow().executorName());
            assertEquals("demo", scheduler.catalog().findJob("demo").orElseThrow().id());
            assertEquals(Instant.parse("2026-07-08T00:00:05Z"),
                    scheduler.repository().find("demo").orElseThrow().nextFireTime());
            assertEquals(0, counter.get());
        }
    }

    @Test
    void rejectsInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> FireflyOptions.builder()
                .workerThreads(0)
                .build());
    }
}
