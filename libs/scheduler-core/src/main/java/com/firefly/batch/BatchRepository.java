package com.firefly.batch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BatchRepository {
    void save(BatchExecution execution);
    Optional<BatchExecution> find(String rootExecutionId);
    boolean saveProgress(String rootExecutionId, BatchProgress progress, long fencingToken);
    boolean saveShardResult(BatchShardResult result, long fencingToken);
    List<BatchShardResult> listShardResults(String rootExecutionId);
    default Optional<BatchExecution> findAt(String rootExecutionId, Instant now) { return find(rootExecutionId).map(value -> new BatchExecution(value.rootExecutionId(), value.jobId(), value.progress(), value.startedAt(), value.deadline())); }
}
