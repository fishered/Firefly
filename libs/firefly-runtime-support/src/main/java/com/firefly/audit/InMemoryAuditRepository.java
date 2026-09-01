package com.firefly.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InMemoryAuditRepository implements AuditRepository {
    private final List<AuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(AuditRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<AuditRecord> listRecent(int limit) {
        return records.stream()
                .sorted(Comparator.comparing(AuditRecord::occurredAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }
}
