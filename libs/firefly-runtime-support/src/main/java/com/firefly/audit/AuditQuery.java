package com.firefly.audit;

import com.firefly.query.PageCursor;

import java.time.Instant;

public record AuditQuery(
        String actor,
        String action,
        String resourceType,
        String outcome,
        Instant fromInclusive,
        Instant toExclusive,
        PageCursor after,
        int limit
) {
    public AuditQuery {
        actor = value(actor);
        action = value(action);
        resourceType = value(resourceType);
        outcome = value(outcome);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
