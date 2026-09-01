package com.firefly.schedule.holiday;

import java.time.LocalDate;
import java.util.Objects;

/** One legal-calendar date with source metadata retained for audit and conflict handling. */
public record HolidayOccurrence(
        LocalDate date,
        HolidayKind kind,
        String localName,
        String name,
        boolean observed,
        String sourceReference
) {
    public HolidayOccurrence {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        if (localName == null || localName.isBlank()) throw new IllegalArgumentException("localName must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (sourceReference == null || sourceReference.isBlank()) throw new IllegalArgumentException("sourceReference must not be blank");
    }
}
