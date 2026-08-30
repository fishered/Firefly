package com.firefly.schedule.holiday;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates provider output before it can become a staged import. */
public final class HolidayDatasetValidator {
    private HolidayDatasetValidator() {
    }

    public static void validate(HolidayQuery query, HolidayDataset dataset) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(dataset, "dataset");
        if (!query.jurisdiction().equalsIgnoreCase(dataset.jurisdiction())) {
            throw new IllegalArgumentException("provider jurisdiction does not match query");
        }
        if (!dataset.from().equals(query.from()) || !dataset.to().equals(query.to())) {
            throw new IllegalArgumentException("provider range does not match query");
        }
        Map<LocalDate, HolidayOccurrence> dates = new HashMap<>();
        for (HolidayOccurrence occurrence : dataset.occurrences()) {
            HolidayOccurrence previous = dates.putIfAbsent(occurrence.date(), occurrence);
            if (previous != null && previous.kind() != occurrence.kind()) {
                throw new IllegalArgumentException("conflicting holiday rules for " + occurrence.date());
            }
        }
    }

    public static List<HolidayOccurrence> deduplicate(List<HolidayOccurrence> occurrences) {
        Map<LocalDate, HolidayOccurrence> dates = new HashMap<>();
        for (HolidayOccurrence occurrence : occurrences) {
            HolidayOccurrence previous = dates.putIfAbsent(occurrence.date(), occurrence);
            if (previous != null && previous.kind() != occurrence.kind()) {
                throw new IllegalArgumentException("conflicting holiday rules for " + occurrence.date());
            }
        }
        return dates.values().stream().sorted(java.util.Comparator.comparing(HolidayOccurrence::date)).toList();
    }
}
