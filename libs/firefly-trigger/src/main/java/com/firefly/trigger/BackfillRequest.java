package com.firefly.trigger;

import java.time.Instant;
import java.util.Objects;

/** Bounded closed interval for historical replay; execution IDs remain root-idempotent. */
public record BackfillRequest(String requestId, String jobId, Instant fromInclusive, Instant toInclusive,
                              int maxExecutions, String rootExecutionId) {
    public BackfillRequest {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128
                || jobId == null || jobId.isBlank() || jobId.length() > 128) {
            throw new IllegalArgumentException("request and job ids must be between 1 and 128 characters");
        }
        Objects.requireNonNull(fromInclusive, "fromInclusive"); Objects.requireNonNull(toInclusive, "toInclusive");
        if (toInclusive.isBefore(fromInclusive)) throw new IllegalArgumentException("backfill interval is reversed");
        if (maxExecutions < 1 || maxExecutions > 1_000_000) throw new IllegalArgumentException("maxExecutions out of bounds");
        rootExecutionId = rootExecutionId == null || rootExecutionId.isBlank() ? requestId : rootExecutionId;
        if (rootExecutionId.length() > 256) {
            throw new IllegalArgumentException("rootExecutionId must not exceed 256 characters");
        }
    }
}
