package com.firefly.governance;

import java.time.Duration;
import java.util.Objects;

/** Side-effect-free proposed tenant policy and analysis parameters. */
public record TenantQuotaProposal(
        long expectedVersion,
        boolean enabled,
        int weight,
        int maxConcurrent,
        long maxCpuMillis,
        long maxMemoryBytes,
        long maxBacklog,
        long maxTriggerRatePerSecond,
        Duration window,
        int headroomPercent
) {
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    public static final int DEFAULT_HEADROOM_PERCENT = 20;

    public TenantQuotaProposal {
        Objects.requireNonNull(window, "window");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        if (weight < 1) throw new IllegalArgumentException("weight must be positive");
        if (maxConcurrent < 0 || maxCpuMillis < 0 || maxMemoryBytes < 0
                || maxBacklog < 0 || maxTriggerRatePerSecond < 0) {
            throw new IllegalArgumentException("proposed quota values must not be negative");
        }
        if (window.compareTo(Duration.ofHours(24)) < 0 || window.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("window must be between PT24H and P30D");
        }
        if (headroomPercent < 5 || headroomPercent > 100) {
            throw new IllegalArgumentException("headroomPercent must be between 5 and 100");
        }
    }

    public long proposedLimit(TenantCapacityResource resource) {
        return switch (resource) {
            case CONCURRENCY -> maxConcurrent;
            case CPU_MILLIS -> maxCpuMillis;
            case MEMORY_BYTES -> maxMemoryBytes;
            case BACKLOG -> maxBacklog;
            case TRIGGER_RATE -> maxTriggerRatePerSecond;
        };
    }
}
