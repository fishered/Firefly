package com.firefly.schedule.holiday;

import java.time.LocalDate;
import java.util.Objects;

/** Normalized calendar rule persisted with provenance instead of opaque CSV only. */
public record CalendarDateRule(
        LocalDate date,
        HolidayKind kind,
        String name,
        RuleSource source,
        boolean locked,
        String importId,
        String reason
) {
    public CalendarDateRule {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (reason == null) reason = "";
        if (source == RuleSource.OFFICIAL && (importId == null || importId.isBlank())) {
            throw new IllegalArgumentException("official rule requires importId");
        }
    }

    public enum RuleSource { OFFICIAL, MANUAL }
}
