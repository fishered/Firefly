package com.firefly.trigger;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Objects;

/** Durable state and immutable inputs for one bounded historical backfill. */
public record BackfillOperation(
        BackfillRequest request,
        BackfillOptions options,
        JobDefinition definition,
        BackfillProgress.BackfillStatus status,
        int expanded,
        int dispatched,
        int failed,
        int cursor,
        int canaryExecutions,
        boolean canaryActive,
        Instant nextAllowedAt,
        String claimOwner,
        Instant claimUntil,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public BackfillOperation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAllowedAt, "nextAllowedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        claimOwner = claimOwner == null ? "" : claimOwner;
        if (expanded < 0 || dispatched < 0 || failed < 0 || cursor < 0
                || cursor > expanded || canaryExecutions < 0 || canaryExecutions > expanded
                || version < 0) {
            throw new IllegalArgumentException("invalid backfill operation counters");
        }
        if (claimOwner.isBlank() != (claimUntil == null)) {
            throw new IllegalArgumentException("claimOwner and claimUntil must be set together");
        }
    }

    public BackfillProgress progress() {
        int released = canaryActive ? canaryExecutions : expanded;
        return new BackfillProgress(request.requestId(), status, expanded, dispatched, failed,
                cursor, Math.max(0, released - cursor), canaryActive);
    }

    public boolean claimedBy(String owner) {
        return owner != null && owner.equals(claimOwner);
    }
}
