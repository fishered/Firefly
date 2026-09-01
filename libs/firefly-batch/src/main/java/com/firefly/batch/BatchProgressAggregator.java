package com.firefly.batch;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Rebuilds aggregate progress from immutable shard attempts; successful carries count once. */
public final class BatchProgressAggregator {
    public BatchProgress aggregate(int totalShards, List<BatchShardResult> attempts, Instant now, BatchSlaStatus sla) {
        Objects.requireNonNull(attempts, "attempts"); Objects.requireNonNull(now, "now");
        int completed=0, failed=0, retried=0; long input=0, output=0;
        java.util.Map<Integer, BatchShardResult> latest = new java.util.HashMap<>();
        for (BatchShardResult result : attempts) {
            BatchShardResult old = latest.put(result.shardIndex(), result.attempt() >= latest.getOrDefault(result.shardIndex(), result).attempt() ? result : latest.get(result.shardIndex()));
            if (old != null && result.attempt() > old.attempt()) retried++;
        }
        for (BatchShardResult result : latest.values()) {
            input += result.inputRecords(); output += result.outputRecords();
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) completed++;
            else if ("FAILED".equalsIgnoreCase(result.status()) || "TIMEOUT".equalsIgnoreCase(result.status())) failed++;
        }
        int percent = totalShards == 0 ? 0 : Math.min(100, (completed * 100) / totalShards);
        BatchSlaStatus effective = percent >= 100 ? BatchSlaStatus.COMPLETE : sla;
        return new BatchProgress(totalShards, completed, failed, retried, input, output, percent, now, effective);
    }
}
