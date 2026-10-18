package com.firefly.trigger;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTriggerInbox implements TriggerInbox {
    private final Map<String, EventTrigger> events = new HashMap<>();
    private final Map<String, String> jobIds = new HashMap<>();
    private final Map<String, String> failureReasons = new HashMap<>();
    private final Map<String, Instant> nonces = new ConcurrentHashMap<>();

    public synchronized boolean receive(EventTrigger trigger) {
        return receive("", trigger);
    }

    public synchronized boolean receive(String jobId, EventTrigger trigger) {
        if (events.containsKey(trigger.idempotencyKey())) return false;
        events.put(trigger.idempotencyKey(), trigger);
        jobIds.put(trigger.idempotencyKey(), jobId == null ? "" : jobId);
        return true;
    }

    public synchronized Optional<EventTrigger> findByIdempotencyKey(String key) {
        return Optional.ofNullable(events.get(key));
    }

    public synchronized boolean markProcessed(String key, Instant at) {
        return update(key, EventTrigger.TriggerStatus.PROCESSED, at);
    }

    public synchronized boolean markFailed(String key, Instant at) {
        return markFailed(key, at, "");
    }

    public synchronized boolean markFailed(String key, Instant at, String reason) {
        if (!events.containsKey(key)) return false;
        failureReasons.put(key, reason == null ? "" : reason);
        return update(key, EventTrigger.TriggerStatus.FAILED, at);
    }

    public boolean reserveNonce(String nonce, Instant expiresAt, Instant now) {
        nonces.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        return nonces.putIfAbsent(nonce, expiresAt) == null;
    }

    public synchronized List<FailedEventTrigger> listFailed(int limit) {
        if (limit <= 0) return List.of();
        return events.values().stream()
                .filter(event -> event.status() == EventTrigger.TriggerStatus.FAILED)
                .sorted(Comparator.comparing(EventTrigger::receivedAt).reversed()
                        .thenComparing(EventTrigger::idempotencyKey, Comparator.reverseOrder()))
                .limit(limit)
                .map(event -> failed(event.idempotencyKey(), event))
                .toList();
    }

    public synchronized Optional<FailedEventTrigger> claimFailedForReplay(String key, Instant claimedAt) {
        EventTrigger value = events.get(key);
        if (value == null || value.status() != EventTrigger.TriggerStatus.FAILED) return Optional.empty();
        FailedEventTrigger claimed = failed(key, value);
        events.put(key, copy(value, EventTrigger.TriggerStatus.RECEIVED, null));
        failureReasons.remove(key);
        return Optional.of(claimed);
    }

    public synchronized boolean acknowledgeFailed(String key, Instant acknowledgedAt) {
        EventTrigger value = events.get(key);
        if (value == null || value.status() != EventTrigger.TriggerStatus.FAILED) return false;
        events.put(key, copy(value, EventTrigger.TriggerStatus.ACKNOWLEDGED, acknowledgedAt));
        return true;
    }

    public synchronized long countFailed() {
        return events.values().stream()
                .filter(event -> event.status() == EventTrigger.TriggerStatus.FAILED).count();
    }

    private boolean update(String key, EventTrigger.TriggerStatus status, Instant at) {
        EventTrigger value = events.get(key);
        if (value == null) return false;
        events.put(key, copy(value, status, at));
        return true;
    }

    private FailedEventTrigger failed(String key, EventTrigger value) {
        return new FailedEventTrigger(value, jobIds.getOrDefault(key, ""),
                failureReasons.getOrDefault(key, ""));
    }

    private EventTrigger copy(EventTrigger value, EventTrigger.TriggerStatus status, Instant at) {
        return new EventTrigger(value.eventId(), value.eventType(), value.idempotencyKey(),
                value.payload(), value.receivedAt(), status, at);
    }
}
