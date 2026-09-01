package com.firefly.engine;

import com.firefly.store.ScheduledJobRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Owner-local due-time index. Durability and ownership remain repository concerns. */
final class SchedulerTimingIndex {
    private final Map<String, ScheduledJobRecord> byJobId = new HashMap<>();
    private final NavigableSet<ScheduledJobRecord> byFireTime = new TreeSet<>(
            Comparator.comparing(ScheduledJobRecord::nextFireTime)
                    .thenComparing(record -> record.definition().id())
    );

    void replace(List<ScheduledJobRecord> records) {
        byJobId.clear();
        byFireTime.clear();
        records.stream().filter(record -> record.definition().enabled()).forEach(this::add);
    }

    void add(ScheduledJobRecord record) {
        ScheduledJobRecord previous = byJobId.put(record.definition().id(), record);
        if (previous != null) byFireTime.remove(previous);
        if (record.definition().enabled()) byFireTime.add(record);
    }

    List<ScheduledJobRecord> pollDue(Instant now, int limit) {
        List<ScheduledJobRecord> due = new ArrayList<>();
        while (due.size() < limit && !byFireTime.isEmpty()) {
            ScheduledJobRecord first = byFireTime.first();
            if (first.nextFireTime().isAfter(now)) break;
            byFireTime.remove(first);
            byJobId.remove(first.definition().id(), first);
            due.add(first);
        }
        return List.copyOf(due);
    }

    Instant nextFireTime() {
        return byFireTime.isEmpty() ? null : byFireTime.first().nextFireTime();
    }
}
