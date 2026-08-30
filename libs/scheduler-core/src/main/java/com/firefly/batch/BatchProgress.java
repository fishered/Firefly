package com.firefly.batch;

import java.time.Instant;

public record BatchProgress(int totalShards, int completedShards, int failedShards, int retriedShards,
                            long inputRecords, long outputRecords, int percent, Instant updatedAt,
                            BatchSlaStatus slaStatus) {
    public BatchProgress {
        if (totalShards < 1 || completedShards < 0 || failedShards < 0 || retriedShards < 0) throw new IllegalArgumentException("invalid shard counters");
        if (completedShards + failedShards > totalShards) throw new IllegalArgumentException("shard counters exceed total");
        percent = Math.max(0, Math.min(100, percent));
        if (updatedAt == null || slaStatus == null) throw new NullPointerException("updatedAt and slaStatus are required");
    }
}
