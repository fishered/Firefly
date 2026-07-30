package com.firefly.domain;

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

    @Test
    void rejectsScheduleTypesThatTheEngineCannotPersistAndExecute() {
        Schedule extensionSchedule = (after, zoneId) -> after.plusSeconds(1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> JobDefinition.builder()
                .id("job-a")
                .name("Job A")
                .handlerName("handler-a")
                .schedule(extensionSchedule)
                .build());

        assertEquals("unsupported schedule type: " + extensionSchedule.getClass().getName(), error.getMessage());
    }

    @Test
    void exposesStableTypesForExecutableSchedules() {
        assertEquals(ScheduleType.CRON, new CronSchedule("0 * * * * *").type());
        assertEquals(ScheduleType.FIXED_RATE, new FixedRateSchedule(Duration.ofSeconds(10)).type());
    }
}

