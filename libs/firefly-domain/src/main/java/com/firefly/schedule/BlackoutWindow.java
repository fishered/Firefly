package com.firefly.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** A recurring local-time interval in which a scheduled fire is blocked. */
public record BlackoutWindow(
        String id,
        Set<DayOfWeek> days,
        LocalTime start,
        LocalTime end,
        BlackoutAction action
) {
    public BlackoutWindow {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        days = Set.copyOf(Objects.requireNonNull(days, "days"));
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(action, "action");
        if (days.isEmpty()) throw new IllegalArgumentException("days must not be empty");
        if (!start.isBefore(end)) throw new IllegalArgumentException("start must be before end");
    }

    public boolean contains(java.time.Instant instant, ZoneId zoneId) {
        var local = instant.atZone(Objects.requireNonNull(zoneId, "zoneId"));
        return days.contains(local.getDayOfWeek())
                && !local.toLocalTime().isBefore(start)
                && local.toLocalTime().isBefore(end);
    }

    public java.time.Instant endInstant(java.time.Instant instant, ZoneId zoneId) {
        var local = instant.atZone(zoneId);
        return local.toLocalDate().atTime(end).atZone(zoneId).toInstant();
    }
}
