package com.firefly.schedule;

import com.firefly.domain.Schedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleSpecParserTest {
    private final ScheduleSpecParser parser = new ScheduleSpecParser();

    @Test
    void parsesFixedRateSpec() {
        Schedule schedule = parser.parse(ScheduleSpec.fixedRate(Duration.ofMinutes(1)));

        assertEquals(Instant.parse("2026-07-08T10:01:00Z"),
                schedule.nextAfter(Instant.parse("2026-07-08T10:00:00Z"), ZoneId.of("UTC")));
    }

    @Test
    void parsesDailyTimeAsCronInJobZone() {
        Schedule schedule = parser.parse(ScheduleSpec.dailyAt(LocalTime.of(1, 0)));

        assertEquals(Instant.parse("2026-07-08T17:00:00Z"),
                schedule.nextAfter(Instant.parse("2026-07-08T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void rejectsStatefulBackoffUntilScheduleStateExists() {
        ScheduleSpec spec = ScheduleSpec.linearBackoff(
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10)
        );

        assertThrows(UnsupportedOperationException.class, () -> parser.parse(spec));
    }
}
