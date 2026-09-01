package com.firefly.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Pure calendar operations; callers keep the runtime cursor as UTC. */
public final class CalendarEvaluator {
    public boolean isWorkingInstant(CalendarDefinition calendar, Instant instant) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(instant, "instant");
        return calendar.isWorkingDay(instant.atZone(calendar.zoneId()).toLocalDate());
    }

    public Instant nextWorkingInstant(CalendarDefinition calendar, Instant instant) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(instant, "instant");
        ZoneId zone = calendar.zoneId();
        var cursor = instant.atZone(zone);
        for (int days = 0; days <= 3660; days++) {
            LocalDate date = cursor.toLocalDate().plusDays(days);
            if (calendar.isWorkingDay(date)) {
                var candidate = date.atTime(cursor.toLocalTime()).atZone(zone).toInstant();
                if (!candidate.isBefore(instant)) return candidate;
            }
        }
        throw new IllegalStateException("calendar has no working date within 10 years");
    }
}
