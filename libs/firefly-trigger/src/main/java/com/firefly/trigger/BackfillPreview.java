package com.firefly.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Read-only expansion result used for operator confirmation before starting a run. */
public record BackfillPreview(String requestId, int expanded, List<Instant> fireTimes,
                              Duration estimatedDuration, int canaryExecutions) {
    public BackfillPreview {
        if (requestId == null || requestId.isBlank() || expanded < 0 || canaryExecutions < 0) {
            throw new IllegalArgumentException("invalid backfill preview");
        }
        fireTimes = List.copyOf(fireTimes);
        if (expanded != fireTimes.size() || canaryExecutions > expanded || estimatedDuration.isNegative()) {
            throw new IllegalArgumentException("invalid backfill preview values");
        }
    }
}
