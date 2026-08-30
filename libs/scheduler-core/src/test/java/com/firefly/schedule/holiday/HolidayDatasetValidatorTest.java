package com.firefly.schedule.holiday;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class HolidayDatasetValidatorTest {
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Test
    void rejectsProviderOutputForAnotherJurisdiction() {
        HolidayQuery query = new HolidayQuery("CN", ZoneId.of("Asia/Shanghai"), FROM, TO, null);
        HolidayDataset dataset = dataset("US-FEDERAL", List.of());
        assertThrows(IllegalArgumentException.class, () -> HolidayDatasetValidator.validate(query, dataset));
    }

    @Test
    void rejectsConflictingRulesOnSameDate() {
        HolidayQuery query = new HolidayQuery("CN", ZoneId.of("Asia/Shanghai"), FROM, TO, null);
        HolidayDataset dataset = dataset("CN", List.of(
                occurrence(HolidayKind.HOLIDAY), occurrence(HolidayKind.WORKDAY)));
        assertThrows(IllegalArgumentException.class, () -> HolidayDatasetValidator.validate(query, dataset));
    }

    @Test
    void deduplicatesEquivalentRules() {
        List<HolidayOccurrence> result = HolidayDatasetValidator.deduplicate(List.of(
                occurrence(HolidayKind.HOLIDAY), occurrence(HolidayKind.HOLIDAY)));
        assertEquals(1, result.size());
    }

    private static HolidayDataset dataset(String jurisdiction, List<HolidayOccurrence> occurrences) {
        return new HolidayDataset("test", "2026.1", jurisdiction, FROM, TO, occurrences,
                URI.create("https://example.invalid/holidays.json"), "sha256:test", true);
    }

    private static HolidayOccurrence occurrence(HolidayKind kind) {
        return new HolidayOccurrence(LocalDate.of(2026, 5, 1), kind, "劳动节", "Labour Day", false,
                "https://example.invalid/holidays/2026#2026-05-01");
    }
}
