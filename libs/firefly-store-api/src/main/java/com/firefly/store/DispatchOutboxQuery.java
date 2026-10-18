package com.firefly.store;

import com.firefly.query.PageCursor;

import java.time.Instant;

public record DispatchOutboxQuery(
        String jobId,
        DispatchType dispatchType,
        Instant fromInclusive,
        Instant toExclusive,
        PageCursor after,
        int limit
) {
    public DispatchOutboxQuery {
        jobId = jobId == null ? "" : jobId.trim();
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
    }
}
