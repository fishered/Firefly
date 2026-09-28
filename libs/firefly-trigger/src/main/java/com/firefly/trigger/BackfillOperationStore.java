package com.firefly.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable, lease-fenced storage boundary for resumable backfill operations. */
public interface BackfillOperationStore {
    boolean create(BackfillOperation operation, List<BackfillItem> items);

    Optional<BackfillOperation> find(String requestId);

    List<BackfillOperation> list(int limit);

    Optional<BackfillOperation> claim(
            String requestId, Instant now, String claimantId, Duration claimLease
    );

    List<BackfillOperation> claimRunnable(
            Instant now, String claimantId, Duration claimLease, int limit
    );

    Optional<BackfillItem> nextItem(String requestId, String claimantId);

    Optional<BackfillOperation> recordItem(
            String requestId,
            String claimantId,
            long expectedVersion,
            BackfillItem.BackfillItemStatus status,
            String error,
            Instant nextAllowedAt,
            Instant now
    );

    boolean release(String requestId, String claimantId, long expectedVersion, Instant now);

    Optional<BackfillOperation> pause(String requestId, Instant now);

    Optional<BackfillOperation> resume(String requestId, Instant now);

    Optional<BackfillOperation> cancel(String requestId, Instant now);

    Optional<BackfillOperation> promote(String requestId, Instant now);
}
