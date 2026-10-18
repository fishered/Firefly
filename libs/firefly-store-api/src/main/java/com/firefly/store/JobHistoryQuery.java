package com.firefly.store;

import com.firefly.query.PageCursor;

import java.time.Instant;

public record JobHistoryQuery(
        String jobId,
        String action,
        Instant fromInclusive,
        Instant toExclusive,
        PageCursor after,
        int limit
) {
    public JobHistoryQuery {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId must not be blank");
        action = action == null ? "" : action.trim();
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
    }
}
