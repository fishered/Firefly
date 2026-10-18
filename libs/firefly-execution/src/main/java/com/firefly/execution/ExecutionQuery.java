package com.firefly.execution;

import com.firefly.query.PageCursor;

import java.time.Instant;

public record ExecutionQuery(
        String jobId,
        ExecutionStatus status,
        Instant fromInclusive,
        Instant toExclusive,
        PageCursor after,
        int limit
) {
    public ExecutionQuery {
        jobId = jobId == null ? "" : jobId.trim();
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
    }
}
