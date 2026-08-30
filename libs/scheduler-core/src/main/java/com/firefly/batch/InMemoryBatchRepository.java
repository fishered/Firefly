package com.firefly.batch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryBatchRepository implements BatchRepository {
    private final Map<String, BatchExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, BatchShardResult>> results = new ConcurrentHashMap<>();
    private final Map<String, Long> fences = new ConcurrentHashMap<>();
    public void save(BatchExecution execution) { executions.putIfAbsent(execution.rootExecutionId(), execution); }
    public Optional<BatchExecution> find(String rootExecutionId) { return Optional.ofNullable(executions.get(rootExecutionId)); }
    public boolean saveProgress(String id, BatchProgress progress, long fencingToken) {
        if (!acceptFence(id, fencingToken)) return false;
        return executions.computeIfPresent(id, (key, value) -> new BatchExecution(value.rootExecutionId(), value.jobId(), progress, value.startedAt(), value.deadline())) != null;
    }
    public boolean saveShardResult(BatchShardResult result, long fencingToken) {
        if (!acceptFence(result.rootExecutionId(), fencingToken)) return false;
        results.computeIfAbsent(result.rootExecutionId(), ignored -> new ConcurrentHashMap<>()).merge(result.shardIndex(), result,
                (oldValue, newValue) -> newValue.attempt() >= oldValue.attempt() ? newValue : oldValue);
        return true;
    }
    public List<BatchShardResult> listShardResults(String id) { return results.getOrDefault(id, Map.of()).values().stream().sorted(Comparator.comparingInt(BatchShardResult::shardIndex)).toList(); }
    private boolean acceptFence(String id, long token) { return fences.merge(id, token, Math::max) <= token; }
}
