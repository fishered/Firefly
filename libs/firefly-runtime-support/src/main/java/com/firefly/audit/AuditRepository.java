package com.firefly.audit;

import java.util.List;

public interface AuditRepository {
    void append(AuditRecord record);

    List<AuditRecord> listRecent(int limit);

    default com.firefly.query.CursorPage<AuditRecord> query(AuditQuery query) {
        List<AuditRecord> values = listRecent(Integer.MAX_VALUE).stream()
                .filter(value -> query.actor().isBlank() || value.actor().equals(query.actor()))
                .filter(value -> query.action().isBlank() || value.action().equals(query.action()))
                .filter(value -> query.resourceType().isBlank() || value.resourceType().equals(query.resourceType()))
                .filter(value -> query.outcome().isBlank() || value.outcome().equals(query.outcome()))
                .filter(value -> query.fromInclusive() == null || !value.occurredAt().isBefore(query.fromInclusive()))
                .filter(value -> query.toExclusive() == null || value.occurredAt().isBefore(query.toExclusive()))
                .filter(value -> query.after() == null || value.occurredAt().isBefore(query.after().timestamp())
                        || (value.occurredAt().equals(query.after().timestamp())
                        && value.auditId().compareTo(query.after().id()) < 0))
                .sorted(java.util.Comparator.comparing(AuditRecord::occurredAt).reversed()
                        .thenComparing(AuditRecord::auditId, java.util.Comparator.reverseOrder()))
                .limit((long) query.limit() + 1).toList();
        return com.firefly.query.CursorPage.of(values, query.limit(),
                value -> new com.firefly.query.PageCursor(value.occurredAt(), value.auditId()));
    }
}
