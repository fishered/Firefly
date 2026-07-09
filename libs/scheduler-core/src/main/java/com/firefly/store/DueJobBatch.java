package com.firefly.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents same-fire-time batch dispatch records that share one next-fire time.
 */
public record DueJobBatch(
        Instant fireTime,
        List<ScheduledJobRecord> records,
        boolean truncated
) {
    public DueJobBatch {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.isEmpty()) {
            fireTime = null;
            truncated = false;
        } else {
            Objects.requireNonNull(fireTime, "fireTime");
            Instant expectedFireTime = fireTime;
            boolean mixedFireTimes = records.stream()
                    .anyMatch(record -> !record.nextFireTime().equals(expectedFireTime));
            if (mixedFireTimes) {
                throw new IllegalArgumentException("all records in a due batch must share the same fireTime");
            }
        }
    }

    public static DueJobBatch empty() {
        return new DueJobBatch(null, List.of(), false);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
