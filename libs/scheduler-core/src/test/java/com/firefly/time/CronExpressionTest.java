package com.firefly.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CronExpressionTest {
    @Test
    void calculatesNextTimeWithJobZone() {
        CronExpression cron = CronExpression.parse("0 0 9 * * *");

        Instant after = Instant.parse("2026-07-06T00:59:59Z");
        Instant next = cron.nextAfter(after, ZoneId.of("Asia/Shanghai"));

        assertEquals(Instant.parse("2026-07-06T01:00:00Z"), next);
    }

    @Test
    void supportsSecondSteps() {
        CronExpression cron = CronExpression.parse("*/5 * * * * *");

        Instant after = Instant.parse("2026-07-06T01:00:01Z");
        Instant next = cron.nextAfter(after, ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-06T01:00:05Z"), next);
    }

    @Test
    void supportsRangesAndLists() {
        CronExpression cron = CronExpression.parse("0 10,20 8-9 * * *");

        Instant after = Instant.parse("2026-07-06T08:10:00Z");
        Instant next = cron.nextAfter(after, ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-06T08:20:00Z"), next);
    }

    @Test
    void supportsLastDayAndNthWeekdayExpressions() {
        CronExpression lastDay = CronExpression.parse("0 0 0 L * *");
        CronExpression secondMonday = CronExpression.parse("0 0 9 * * MON#2");

        assertEquals(
                Instant.parse("2026-07-31T00:00:00Z"),
                lastDay.nextAfter(Instant.parse("2026-07-01T00:00:00Z"), ZoneId.of("UTC"))
        );
        assertEquals(
                Instant.parse("2026-07-13T09:00:00Z"),
                secondMonday.nextAfter(Instant.parse("2026-07-01T00:00:00Z"), ZoneId.of("UTC"))
        );
    }

    @Test
    void rejectsInvalidFieldCount() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * * *"));
    }

    @Test
    void rejectsOutOfRangeValue() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("60 * * * * *"));
    }

    @Test
    void skipsNonexistentLocalTimeDuringDstSpringForward() {
        CronExpression cron = CronExpression.parse("0 30 2 * * *");

        Instant after = Instant.parse("2026-03-08T06:59:59Z");
        Instant next = cron.nextAfter(after, ZoneId.of("America/New_York"));

        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), next);
    }

    @Test
    void firesBothRepeatedLocalTimesDuringDstFallBack() {
        CronExpression cron = CronExpression.parse("0 30 1 * * *");
        ZoneId zone = ZoneId.of("America/New_York");

        Instant first = cron.nextAfter(Instant.parse("2026-11-01T05:00:00Z"), zone);
        Instant second = cron.nextAfter(first, zone);

        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first);
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), second);
    }
}

