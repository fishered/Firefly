package com.firefly.schedule.holiday;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Bounded request passed to a holiday provider; timezone and jurisdiction are independent. */
public record HolidayQuery(
        String jurisdiction,
        ZoneId zoneId,
        LocalDate from,
        LocalDate to,
        String expectedProviderVersion
) {
    public HolidayQuery {
        if (jurisdiction == null || jurisdiction.isBlank()) throw new IllegalArgumentException("jurisdiction must not be blank");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        if (ChronoUnit.DAYS.between(from, to) > 3660) throw new IllegalArgumentException("holiday query cannot exceed 10 years");
    }
}
