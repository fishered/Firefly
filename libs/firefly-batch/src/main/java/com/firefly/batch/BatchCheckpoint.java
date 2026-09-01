package com.firefly.batch;

import java.time.Instant;

public record BatchCheckpoint(String checkpointId, String rootExecutionId, int shardIndex, int attempt,
                              String location, String checksum, Instant createdAt) {
    public BatchCheckpoint {
        if (checkpointId == null || checkpointId.isBlank() || rootExecutionId == null || rootExecutionId.isBlank()) throw new IllegalArgumentException("checkpoint ids must not be blank");
        if (shardIndex < 0 || attempt < 0) throw new IllegalArgumentException("invalid checkpoint identity");
        if (location == null || location.isBlank() || checksum == null || checksum.isBlank() || createdAt == null) throw new IllegalArgumentException("checkpoint metadata is required");
    }
}
