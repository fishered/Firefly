package com.firefly.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Versioned business calendar evaluated in the job's explicit time zone. */
public record CalendarDefinition(
        String id,
        long version,
        ZoneId zoneId,
        Set<DayOfWeek> workingDays,
        Set<LocalDate> holidays,
        Set<LocalDate> extraWorkingDays
) {
    public CalendarDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(zoneId, "zoneId");
        workingDays = Set.copyOf(Objects.requireNonNull(workingDays, "workingDays"));
        holidays = Set.copyOf(Objects.requireNonNull(holidays, "holidays"));
        extraWorkingDays = Set.copyOf(Objects.requireNonNull(extraWorkingDays, "extraWorkingDays"));
        if (workingDays.isEmpty()) throw new IllegalArgumentException("workingDays must not be empty");
    }

    public static CalendarDefinition mondayToFriday(String id, ZoneId zoneId) {
        return new CalendarDefinition(id, 1, zoneId,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), Set.of(), Set.of());
    }

    public boolean isWorkingDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return (extraWorkingDays.contains(date) || workingDays.contains(date)) && !holidays.contains(date);
    }
}
