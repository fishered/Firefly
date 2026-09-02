package com.firefly.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulingInputRevisionTest {
    @Test
    void reportsVersionedInputDifferencesForReplayPreviews() {
        Instant effective = Instant.parse("2026-09-02T00:00:00Z");
        SchedulingInputRevision before = new SchedulingInputRevision("orders", 12, 4, 7, effective);
        SchedulingInputRevision after = new SchedulingInputRevision("orders", 15, 6, 7, effective);
        assertEquals(List.of("jobRevision", "calendarRevision"), before.differences(after));
    }
}
