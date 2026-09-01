package com.firefly.batch;

import java.time.Duration;
import java.time.Instant;

public record BatchExecution(String rootExecutionId, String jobId, BatchProgress progress,
                             Instant startedAt, Instant deadline) {
    public BatchExecution {
        if (rootExecutionId == null || rootExecutionId.isBlank() || jobId == null || jobId.isBlank()) throw new IllegalArgumentException("execution identity is required");
        if (progress == null || startedAt == null || deadline == null) throw new NullPointerException("progress and times are required");
        if (deadline.isBefore(startedAt)) throw new IllegalArgumentException("deadline before start");
    }
    public BatchSlaStatus slaAt(Instant now) {
        if (progress.percent() >= 100) return BatchSlaStatus.COMPLETE;
        if (!now.isBefore(deadline)) return BatchSlaStatus.BREACHED;
        long total = Duration.between(startedAt, deadline).toMillis();
        long elapsed = Duration.between(startedAt, now).toMillis();
        return elapsed * 100 / Math.max(1, total) > progress.percent() + 10 ? BatchSlaStatus.AT_RISK : BatchSlaStatus.ON_TRACK;
    }
}
