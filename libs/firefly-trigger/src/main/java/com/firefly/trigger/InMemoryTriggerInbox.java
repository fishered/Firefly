package com.firefly.trigger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTriggerInbox implements TriggerInbox {
    private final Map<String, EventTrigger> events = new ConcurrentHashMap<>();
    public boolean receive(EventTrigger trigger) { return events.putIfAbsent(trigger.idempotencyKey(), trigger) == null; }
    public Optional<EventTrigger> findByIdempotencyKey(String key) { return Optional.ofNullable(events.get(key)); }
    public boolean markProcessed(String key, Instant at) { return update(key, EventTrigger.TriggerStatus.PROCESSED, at); }
    public boolean markFailed(String key, Instant at) { return update(key, EventTrigger.TriggerStatus.FAILED, at); }
    private boolean update(String key, EventTrigger.TriggerStatus status, Instant at) {
        return events.computeIfPresent(key, (ignored, value) -> new EventTrigger(value.eventId(), value.eventType(), value.idempotencyKey(), value.payload(), value.receivedAt(), status, at)) != null;
    }
}
