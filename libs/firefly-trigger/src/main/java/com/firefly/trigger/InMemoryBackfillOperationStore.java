package com.firefly.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory implementation with the same lease and fencing behavior as JDBC. */
public final class InMemoryBackfillOperationStore implements BackfillOperationStore {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized boolean create(BackfillOperation operation, List<BackfillItem> items) {
        String id = operation.request().requestId();
        if (entries.containsKey(id)) return false;
        if (operation.expanded() != items.size()) {
            throw new IllegalArgumentException("backfill item count does not match expanded count");
        }
        entries.put(id, new Entry(operation, new ArrayList<>(items)));
        return true;
    }

    @Override
    public synchronized Optional<BackfillOperation> find(String requestId) {
        Entry entry = entries.get(requestId);
        return entry == null ? Optional.empty() : Optional.of(entry.operation);
    }

    @Override
    public synchronized List<BackfillOperation> list(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return entries.values().stream().map(entry -> entry.operation)
                .sorted(Comparator.comparing(BackfillOperation::createdAt).reversed()
                        .thenComparing(operation -> operation.request().requestId()))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<BackfillOperation> claim(
            String requestId, Instant now, String claimantId, Duration claimLease
    ) {
        validateClaim(now, claimantId, claimLease);
        Entry entry = entries.get(requestId);
        if (entry == null || !runnable(entry.operation, now) || !claimAvailable(entry.operation, now)) {
            return Optional.empty();
        }
        entry.operation = claimed(entry.operation, now, claimantId, claimLease);
        return Optional.of(entry.operation);
    }

    @Override
    public synchronized List<BackfillOperation> claimRunnable(
            Instant now, String claimantId, Duration claimLease, int limit
    ) {
        validateClaim(now, claimantId, claimLease);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<BackfillOperation> claimed = new ArrayList<>();
        for (Entry entry : entries.values().stream()
                .sorted(Comparator.comparing(value -> value.operation.createdAt())).toList()) {
            if (claimed.size() >= limit) break;
            if (!runnable(entry.operation, now) || !claimAvailable(entry.operation, now)) continue;
            entry.operation = claimed(entry.operation, now, claimantId, claimLease);
            claimed.add(entry.operation);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized Optional<BackfillItem> nextItem(String requestId, String claimantId) {
        Entry entry = entries.get(requestId);
        if (entry == null || !entry.operation.claimedBy(claimantId)
                || entry.operation.cursor() >= entry.items.size()) return Optional.empty();
        return Optional.of(entry.items.get(entry.operation.cursor()));
    }

    @Override
    public synchronized Optional<BackfillOperation> recordItem(
            String requestId,
            String claimantId,
            long expectedVersion,
            BackfillItem.BackfillItemStatus status,
            String error,
            Instant nextAllowedAt,
            Instant now
    ) {
        Entry entry = entries.get(requestId);
        if (entry == null || !entry.operation.claimedBy(claimantId)
                || entry.operation.version() != expectedVersion || entry.operation.cursor() >= entry.items.size()) {
            return Optional.empty();
        }
        BackfillOperation current = entry.operation;
        int ordinal = current.cursor();
        BackfillItem item = entry.items.get(ordinal);
        entry.items.set(ordinal, new BackfillItem(item.ordinal(), item.fireTime(), item.executionId(), status, error));
        int cursor = ordinal + 1;
        int dispatched = current.dispatched() + (status == BackfillItem.BackfillItemStatus.DISPATCHED ? 1 : 0);
        int failed = current.failed() + (status == BackfillItem.BackfillItemStatus.FAILED ? 1 : 0);
        BackfillProgress.BackfillStatus operationStatus = BackfillProgress.BackfillStatus.RUNNING;
        String owner = claimantId;
        Instant claimUntil = current.claimUntil();
        if (cursor >= current.expanded()) {
            operationStatus = BackfillProgress.BackfillStatus.COMPLETED;
            owner = "";
            claimUntil = null;
        } else if (current.canaryActive() && cursor >= current.canaryExecutions()) {
            operationStatus = BackfillProgress.BackfillStatus.PAUSED;
            owner = "";
            claimUntil = null;
        }
        entry.operation = copy(current, operationStatus, dispatched, failed, cursor,
                current.canaryActive(), nextAllowedAt, owner, claimUntil, current.version() + 1, now);
        return Optional.of(entry.operation);
    }

    @Override
    public synchronized boolean release(String requestId, String claimantId, long expectedVersion, Instant now) {
        Entry entry = entries.get(requestId);
        if (entry == null || !entry.operation.claimedBy(claimantId)
                || entry.operation.version() != expectedVersion) return false;
        BackfillOperation current = entry.operation;
        entry.operation = copy(current, current.status(), current.dispatched(), current.failed(), current.cursor(),
                current.canaryActive(), current.nextAllowedAt(), "", null, current.version() + 1, now);
        return true;
    }

    @Override
    public synchronized Optional<BackfillOperation> pause(String requestId, Instant now) {
        return transition(requestId, now, Transition.PAUSE);
    }

    @Override
    public synchronized Optional<BackfillOperation> resume(String requestId, Instant now) {
        return transition(requestId, now, Transition.RESUME);
    }

    @Override
    public synchronized Optional<BackfillOperation> cancel(String requestId, Instant now) {
        return transition(requestId, now, Transition.CANCEL);
    }

    @Override
    public synchronized Optional<BackfillOperation> promote(String requestId, Instant now) {
        return transition(requestId, now, Transition.PROMOTE);
    }

    private Optional<BackfillOperation> transition(String requestId, Instant now, Transition transition) {
        Entry entry = entries.get(requestId);
        if (entry == null) return Optional.empty();
        BackfillOperation current = entry.operation;
        BackfillProgress.BackfillStatus status = current.status();
        boolean canary = current.canaryActive();
        switch (transition) {
            case PAUSE -> {
                if (status == BackfillProgress.BackfillStatus.PENDING
                        || status == BackfillProgress.BackfillStatus.RUNNING) {
                    status = BackfillProgress.BackfillStatus.PAUSED;
                }
            }
            case RESUME -> {
                if (status == BackfillProgress.BackfillStatus.PAUSED
                        && (!canary || current.cursor() < current.canaryExecutions())) {
                    status = BackfillProgress.BackfillStatus.RUNNING;
                }
            }
            case CANCEL -> {
                if (!status.terminal()) status = BackfillProgress.BackfillStatus.CANCELLED;
            }
            case PROMOTE -> {
                canary = false;
                if (status == BackfillProgress.BackfillStatus.PENDING
                        || status == BackfillProgress.BackfillStatus.PAUSED) {
                    status = BackfillProgress.BackfillStatus.RUNNING;
                }
            }
        }
        entry.operation = copy(current, status, current.dispatched(), current.failed(), current.cursor(),
                canary, current.nextAllowedAt(), "", null, current.version() + 1, now);
        return Optional.of(entry.operation);
    }

    private static boolean runnable(BackfillOperation operation, Instant now) {
        return (operation.status() == BackfillProgress.BackfillStatus.PENDING
                || operation.status() == BackfillProgress.BackfillStatus.RUNNING)
                && !operation.nextAllowedAt().isAfter(now);
    }

    private static boolean claimAvailable(BackfillOperation operation, Instant now) {
        return operation.claimOwner().isBlank() || !operation.claimUntil().isAfter(now);
    }

    private static BackfillOperation claimed(
            BackfillOperation current, Instant now, String claimantId, Duration claimLease
    ) {
        return copy(current, BackfillProgress.BackfillStatus.RUNNING, current.dispatched(), current.failed(),
                current.cursor(), current.canaryActive(), current.nextAllowedAt(), claimantId,
                now.plus(claimLease), current.version() + 1, now);
    }

    private static BackfillOperation copy(
            BackfillOperation current,
            BackfillProgress.BackfillStatus status,
            int dispatched,
            int failed,
            int cursor,
            boolean canary,
            Instant nextAllowedAt,
            String owner,
            Instant claimUntil,
            long version,
            Instant updatedAt
    ) {
        return new BackfillOperation(current.request(), current.options(), current.definition(), status,
                current.expanded(), dispatched, failed, cursor, current.canaryExecutions(), canary,
                nextAllowedAt, owner, claimUntil, version, current.createdAt(), updatedAt);
    }

    private static void validateClaim(Instant now, String claimantId, Duration claimLease) {
        if (now == null) throw new NullPointerException("now");
        if (claimantId == null || claimantId.isBlank() || claimantId.length() > 128) {
            throw new IllegalArgumentException("claimantId must be between 1 and 128 characters");
        }
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    private enum Transition { PAUSE, RESUME, CANCEL, PROMOTE }

    private static final class Entry {
        private BackfillOperation operation;
        private final List<BackfillItem> items;

        private Entry(BackfillOperation operation, List<BackfillItem> items) {
            this.operation = operation;
            this.items = items;
        }
    }
}
