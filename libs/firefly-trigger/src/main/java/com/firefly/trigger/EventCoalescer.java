package com.firefly.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe in-memory aggregation store with leased, retryable releases. */
public final class EventCoalescer implements EventAggregationStore {
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Claimed> claimed = new HashMap<>();
    private final Map<String, String> eventMembership = new HashMap<>();

    @Override
    public synchronized Optional<AggregatedEvent> add(
            String jobId,
            EventTrigger trigger,
            EventAggregationPolicy policy,
            Instant now,
            String claimantId,
            Duration claimLease
    ) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        validateClaim(claimantId, claimLease);
        if (eventMembership.containsKey(trigger.idempotencyKey())) return Optional.empty();
        String groupKey = groupKey(jobId, policy.aggregationKey());
        Pending current = pending.get(groupKey);
        if (current == null) {
            current = new Pending(UUID.randomUUID().toString(), jobId, policy.aggregationKey(),
                    trigger.payload(), 1, now, now.plus(policy.debounceWindow()), now.plus(policy.maxDelay()),
                    new ArrayList<>(List.of(trigger.idempotencyKey())));
        } else {
            current = new Pending(current.aggregateId, current.jobId, current.aggregationKey,
                    trigger.payload(), current.eventCount + 1, current.firstReceivedAt,
                    now.plus(policy.debounceWindow()), current.deadlineAt,
                    append(current.idempotencyKeys, trigger.idempotencyKey()));
        }
        pending.put(groupKey, current);
        eventMembership.put(trigger.idempotencyKey(), current.aggregateId);
        if (now.isBefore(current.readyAt) && now.isBefore(current.deadlineAt)) {
            return Optional.empty();
        }
        pending.remove(groupKey);
        claimed.put(current.aggregateId, new Claimed(current, claimantId, now.plus(claimLease)));
        return Optional.of(current.snapshot());
    }

    @Override
    public synchronized List<AggregatedEvent> claimDue(
            Instant now, String claimantId, Duration claimLease, int limit
    ) {
        Objects.requireNonNull(now, "now");
        validateClaim(claimantId, claimLease);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<Candidate> candidates = new ArrayList<>();
        pending.forEach((groupKey, value) -> {
            if (!now.isBefore(value.readyAt) || !now.isBefore(value.deadlineAt)) {
                candidates.add(new Candidate(groupKey, value, false));
            }
        });
        claimed.forEach((aggregateId, value) -> {
            if (!now.isBefore(value.claimUntil)) {
                candidates.add(new Candidate(aggregateId, value.pending, true));
            }
        });
        candidates.sort(Comparator.comparing((Candidate value) -> value.pending.readyAt)
                .thenComparing(value -> value.pending.aggregateId));
        List<AggregatedEvent> result = new ArrayList<>();
        for (Candidate candidate : candidates.stream().limit(limit).toList()) {
            Pending value = candidate.pending;
            if (!candidate.previouslyClaimed) pending.remove(candidate.key);
            claimed.put(value.aggregateId, new Claimed(value, claimantId, now.plus(claimLease)));
            result.add(value.snapshot());
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean complete(String aggregateId, String claimantId) {
        Claimed value = claimed.get(aggregateId);
        if (value == null || !value.claimantId.equals(claimantId)) return false;
        claimed.remove(aggregateId);
        value.pending.idempotencyKeys.forEach(eventMembership::remove);
        return true;
    }

    @Override
    public synchronized boolean release(String aggregateId, String claimantId, Instant now) {
        Objects.requireNonNull(now, "now");
        Claimed value = claimed.get(aggregateId);
        if (value == null || !value.claimantId.equals(claimantId)) return false;
        claimed.put(aggregateId, new Claimed(value.pending, claimantId, now));
        return true;
    }

    public synchronized int pendingGroups() {
        return pending.size() + claimed.size();
    }

    private static void validateClaim(String claimantId, Duration claimLease) {
        if (claimantId == null || claimantId.isBlank()) {
            throw new IllegalArgumentException("claimantId must not be blank");
        }
        Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    private static String groupKey(String jobId, String aggregationKey) {
        return jobId + "\u0000" + aggregationKey;
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private record Pending(
            String aggregateId,
            String jobId,
            String aggregationKey,
            String latestPayload,
            int eventCount,
            Instant firstReceivedAt,
            Instant readyAt,
            Instant deadlineAt,
            List<String> idempotencyKeys
    ) {
        AggregatedEvent snapshot() {
            return new AggregatedEvent(aggregateId, jobId, aggregationKey, latestPayload, eventCount,
                    firstReceivedAt, readyAt, idempotencyKeys);
        }
    }

    private record Claimed(Pending pending, String claimantId, Instant claimUntil) { }

    private record Candidate(String key, Pending pending, boolean previouslyClaimed) { }
}
