package com.firefly.store;

import java.util.List;

public interface JobHistoryRepository {
    void append(JobHistoryRecord record);

    List<JobHistoryRecord> listByJob(String jobId, int limit);
}
