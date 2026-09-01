package com.firefly.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InMemoryJobHistoryRepository implements JobHistoryRepository {
    private final List<JobHistoryRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(JobHistoryRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<JobHistoryRecord> listByJob(String jobId, int limit) {
        return records.stream()
                .filter(record -> record.jobId().equals(jobId))
                .sorted(Comparator.comparing(JobHistoryRecord::occurredAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }
}
