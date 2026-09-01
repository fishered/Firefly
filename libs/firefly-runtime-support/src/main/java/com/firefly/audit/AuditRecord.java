package com.firefly.audit;

import java.time.Instant;
import java.util.Objects;

/** Durable record of an administrative mutation and its outcome. */
public record AuditRecord(
        String auditId,
        Instant occurredAt,
        String actor,
        String role,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String beforePayload,
        String afterPayload,
        String detail
) {
    public AuditRecord {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        actor = value(actor);
        role = value(role);
        action = value(action);
        resourceType = value(resourceType);
        resourceId = value(resourceId);
        outcome = value(outcome);
        beforePayload = value(beforePayload);
        afterPayload = value(afterPayload);
        detail = value(detail);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
