package com.firefly.trigger;

import java.time.Instant;
import java.util.Optional;

/** Idempotent event boundary. Implementations must enforce uniqueness on idempotencyKey. */
public interface TriggerInbox {
    boolean receive(EventTrigger trigger);
    Optional<EventTrigger> findByIdempotencyKey(String key);
    boolean markProcessed(String key, Instant processedAt);
    boolean markFailed(String key, Instant processedAt);
}
