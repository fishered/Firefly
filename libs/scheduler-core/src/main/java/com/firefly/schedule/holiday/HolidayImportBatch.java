package com.firefly.schedule.holiday;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Lifecycle metadata for a staged or published holiday import. */
public record HolidayImportBatch(
        String importId,
        String calendarId,
        long targetVersion,
        String providerId,
        String providerVersion,
        String jurisdiction,
        ZoneId zoneId,
        LocalDate from,
        LocalDate to,
        String sourceUri,
        String sourceChecksum,
        ImportStatus status,
        int conflictCount,
        Instant importedAt,
        String importedBy,
        Instant publishedAt
) {
    public HolidayImportBatch {
        for (String value : new String[]{importId, calendarId, providerId, providerVersion, jurisdiction, sourceUri, sourceChecksum, importedBy}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("import metadata must not be blank");
        }
        if (targetVersion < 1) throw new IllegalArgumentException("targetVersion must be positive");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        Objects.requireNonNull(status, "status");
        if (conflictCount < 0) throw new IllegalArgumentException("conflictCount must not be negative");
        Objects.requireNonNull(importedAt, "importedAt");
        if (status == ImportStatus.PUBLISHED && publishedAt == null) throw new IllegalArgumentException("published import requires publishedAt");
    }

    public enum ImportStatus { STAGED, REJECTED, PUBLISHED }
}
