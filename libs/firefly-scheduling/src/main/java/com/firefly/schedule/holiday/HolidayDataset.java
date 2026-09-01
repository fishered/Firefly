package com.firefly.schedule.holiday;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Immutable, verified dataset staged before it is projected into a calendar version. */
public record HolidayDataset(
        String providerId,
        String providerVersion,
        String jurisdiction,
        LocalDate from,
        LocalDate to,
        List<HolidayOccurrence> occurrences,
        URI sourceUri,
        String checksum,
        boolean official
) {
    public HolidayDataset {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
        if (providerVersion == null || providerVersion.isBlank()) throw new IllegalArgumentException("providerVersion must not be blank");
        if (jurisdiction == null || jurisdiction.isBlank()) throw new IllegalArgumentException("jurisdiction must not be blank");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
        Objects.requireNonNull(sourceUri, "sourceUri");
        if (checksum == null || checksum.isBlank()) throw new IllegalArgumentException("checksum must not be blank");
        if (!official) throw new IllegalArgumentException("only verified official datasets may be imported");
        for (HolidayOccurrence occurrence : occurrences) {
            if (occurrence.date().isBefore(from) || occurrence.date().isAfter(to)) {
                throw new IllegalArgumentException("occurrence is outside dataset range: " + occurrence.date());
            }
        }
    }
}
