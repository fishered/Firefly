package io.github.nishi.firefly.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobDefinitionTest {
    @Test
    void defaultsAreLightweightAndSafe() {
        JobDefinition job = JobDefinition.builder()
                .id("job-a")
                .name("Job A")
                .handlerName("handler-a")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(10)))
                .build();

        assertEquals(ZoneId.of("UTC"), job.zoneId());
        assertEquals(MisfirePolicy.FIRE_ONCE, job.misfirePolicy());
        assertEquals(ConcurrencyPolicy.FORBID, job.concurrencyPolicy());
        assertEquals(Duration.ofMinutes(5), job.timeout());
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> JobDefinition.builder()
                .id(" ")
                .name("Job A")
                .handlerName("handler-a")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(10)))
                .build());
    }

    @Test
    void rejectsNonPositiveCatchUpCount() {
        assertThrows(IllegalArgumentException.class, () -> JobDefinition.builder()
                .id("job-a")
                .name("Job A")
                .handlerName("handler-a")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(10)))
                .maxCatchUpCount(0)
                .build());
    }
}

