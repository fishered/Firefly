package com.firefly.batch;

import java.time.Instant;
import java.util.Objects;

public record BatchShardResult(String rootExecutionId, int shardIndex, int attempt, String status,
                               long inputRecords, long outputRecords, String checkpointId, String checksum,
                               String errorMessage, Instant completedAt) {
    public BatchShardResult {
        if (rootExecutionId == null || rootExecutionId.isBlank() || shardIndex < 0 || attempt < 0) throw new IllegalArgumentException("invalid shard identity");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        if (inputRecords < 0 || outputRecords < 0) throw new IllegalArgumentException("record counts must not be negative");
        checkpointId = Objects.requireNonNullElse(checkpointId, ""); checksum = Objects.requireNonNullElse(checksum, ""); errorMessage = Objects.requireNonNullElse(errorMessage, "");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
