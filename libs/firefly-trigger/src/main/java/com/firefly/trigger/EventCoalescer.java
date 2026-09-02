package com.firefly.trigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe in-memory event window. Persistence remains behind TriggerInbox;
 * callers should periodically invoke {@link #flushDue(Instant)} from a worker.
 */
public final class EventCoalescer {
    private final Map<String, Pending> pending = new HashMap<>();

    public synchronized java.util.Optional<AggregatedEvent> add(
            String jobId, EventTrigger trigger, EventAggregationPolicy policy, Instant now
    ) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        String key = jobId + "\u0000" + policy.aggregationKey();
        Pending current = pending.get(key);
        if (current == null) {
            current = new Pending(jobId, policy.aggregationKey(), trigger.payload(), 1,
                    now, now.plus(policy.debounceWindow()), now.plus(policy.maxDelay()),
                    new ArrayList<>(List.of(trigger.idempotencyKey())));
        } else {
            current = new Pending(current.jobId, current.aggregationKey, trigger.payload(), current.eventCount + 1,
                    current.firstReceivedAt, now.plus(policy.debounceWindow()), current.deadlineAt,
                    append(current.idempotencyKeys, trigger.idempotencyKey()));
        }
        if (!now.isBefore(current.readyAt) || !now.isBefore(current.deadlineAt)) {
            pending.remove(key);
            return java.util.Optional.of(current.snapshot());
        }
        pending.put(key, current);
        return java.util.Optional.empty();
    }

    public synchronized List<AggregatedEvent> flushDue(Instant now) {
        Objects.requireNonNull(now, "now");
        List<AggregatedEvent> released = new ArrayList<>();
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            if (now.isBefore(value.readyAt) && now.isBefore(value.deadlineAt)) return false;
            released.add(value.snapshot());
            return true;
        });
        released.sort(Comparator.comparing(AggregatedEvent::readyAt).thenComparing(AggregatedEvent::jobId));
        return List.copyOf(released);
    }

    public synchronized int pendingGroups() {
        return pending.size();
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private record Pending(
            String jobId, String aggregationKey, String latestPayload, int eventCount,
            Instant firstReceivedAt, Instant readyAt, Instant deadlineAt, List<String> idempotencyKeys
    ) {
        AggregatedEvent snapshot() {
            return new AggregatedEvent(jobId, aggregationKey, latestPayload, eventCount,
                    firstReceivedAt, readyAt, idempotencyKeys);
        }
    }
}
