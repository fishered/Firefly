package com.firefly.trigger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Idempotent event boundary. Implementations must enforce uniqueness on idempotencyKey. */
public interface TriggerInbox {
    boolean receive(EventTrigger trigger);
    default boolean receive(String jobId, EventTrigger trigger) { return receive(trigger); }
    Optional<EventTrigger> findByIdempotencyKey(String key);
    boolean markProcessed(String key, Instant processedAt);
    boolean markFailed(String key, Instant processedAt);

    default boolean markFailed(String key, Instant processedAt, String failureReason) {
        return markFailed(key, processedAt);
    }

    /** Atomically reserves a signed-request nonce until its expiry. */
    default boolean reserveNonce(String nonce, Instant expiresAt, Instant now) {
        throw new UnsupportedOperationException("nonce persistence is not implemented");
    }

    default List<FailedEventTrigger> listFailed(int limit) {
        return List.of();
    }

    /** Atomically moves a failed event back to RECEIVED for one replay worker. */
    default Optional<FailedEventTrigger> claimFailedForReplay(String key, Instant claimedAt) {
        return Optional.empty();
    }

    default boolean acknowledgeFailed(String key, Instant acknowledgedAt) {
        return false;
    }

    default long countFailed() {
        return listFailed(Integer.MAX_VALUE).size();
    }
}
