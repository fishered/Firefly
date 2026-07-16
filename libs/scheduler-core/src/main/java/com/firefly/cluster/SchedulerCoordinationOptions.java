package com.firefly.cluster;

import java.time.Duration;
import java.util.Objects;

/** Timing contract for scheduler membership and shard lease coordination. */
public record SchedulerCoordinationOptions(
        Duration reconcileInterval,
        Duration nodeTimeout,
        Duration leaseDuration
) {
    public SchedulerCoordinationOptions {
        Objects.requireNonNull(reconcileInterval, "reconcileInterval");
        Objects.requireNonNull(nodeTimeout, "nodeTimeout");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        requirePositive(reconcileInterval, "reconcileInterval");
        requirePositive(nodeTimeout, "nodeTimeout");
        requirePositive(leaseDuration, "leaseDuration");
        if (nodeTimeout.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("nodeTimeout must be shorter than leaseDuration");
        }
        if (leaseDuration.compareTo(reconcileInterval.multipliedBy(2)) <= 0) {
            throw new IllegalArgumentException("leaseDuration must exceed two reconcile intervals");
        }
    }

    public static SchedulerCoordinationOptions defaults() {
        return new SchedulerCoordinationOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10)
        );
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
