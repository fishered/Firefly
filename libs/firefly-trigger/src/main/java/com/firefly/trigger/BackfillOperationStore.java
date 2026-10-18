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

    default com.firefly.query.CursorPage<BackfillOperation> query(BackfillQuery query) {
        List<BackfillOperation> values = list(Integer.MAX_VALUE).stream()
                .filter(value -> query.jobId().isBlank() || value.request().jobId().equals(query.jobId()))
                .filter(value -> query.status() == null || value.status() == query.status())
                .filter(value -> query.fromInclusive() == null || !value.createdAt().isBefore(query.fromInclusive()))
                .filter(value -> query.toExclusive() == null || value.createdAt().isBefore(query.toExclusive()))
                .filter(value -> query.after() == null || value.createdAt().isBefore(query.after().timestamp())
                        || (value.createdAt().equals(query.after().timestamp())
                        && value.request().requestId().compareTo(query.after().id()) < 0))
                .sorted(java.util.Comparator.comparing(BackfillOperation::createdAt).reversed()
                        .thenComparing(value -> value.request().requestId(), java.util.Comparator.reverseOrder()))
                .limit((long) query.limit() + 1).toList();
        return com.firefly.query.CursorPage.of(values, query.limit(), value ->
                new com.firefly.query.PageCursor(value.createdAt(), value.request().requestId()));
    }

    Optional<BackfillOperation> claim(
            String requestId, Instant now, String claimantId, Duration claimLease
    );

    List<BackfillOperation> claimRunnable(
            Instant now, String claimantId, Duration claimLease, int limit
    );

    Optional<BackfillItem> nextItem(String requestId, String claimantId);

    default List<BackfillItem> listItems(String requestId, int limit) {
        return List.of();
    }

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
