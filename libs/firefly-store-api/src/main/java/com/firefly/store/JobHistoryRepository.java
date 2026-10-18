package com.firefly.store;

import java.util.List;

public interface JobHistoryRepository {
    void append(JobHistoryRecord record);

    List<JobHistoryRecord> listByJob(String jobId, int limit);

    default com.firefly.query.CursorPage<JobHistoryRecord> query(JobHistoryQuery query) {
        List<JobHistoryRecord> values = listByJob(query.jobId(), Integer.MAX_VALUE).stream()
                .filter(value -> query.action().isBlank() || value.action().equals(query.action()))
                .filter(value -> query.fromInclusive() == null || !value.occurredAt().isBefore(query.fromInclusive()))
                .filter(value -> query.toExclusive() == null || value.occurredAt().isBefore(query.toExclusive()))
                .filter(value -> query.after() == null || value.occurredAt().isBefore(query.after().timestamp())
                        || (value.occurredAt().equals(query.after().timestamp())
                        && value.historyId().compareTo(query.after().id()) < 0))
                .sorted(java.util.Comparator.comparing(JobHistoryRecord::occurredAt).reversed()
                        .thenComparing(JobHistoryRecord::historyId, java.util.Comparator.reverseOrder()))
                .limit((long) query.limit() + 1).toList();
        return com.firefly.query.CursorPage.of(values, query.limit(),
                value -> new com.firefly.query.PageCursor(value.occurredAt(), value.historyId()));
    }
}
