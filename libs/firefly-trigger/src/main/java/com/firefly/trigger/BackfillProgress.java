package com.firefly.trigger;

/** Durable-shaped progress view; the coordinator can be replaced by a JDBC store later. */
public record BackfillProgress(String requestId, BackfillStatus status, int expanded,
                               int dispatched, int failed, int cursor, int remaining,
                               boolean canary) {
    public BackfillProgress {
        if (requestId == null || requestId.isBlank() || status == null
                || expanded < 0 || dispatched < 0 || failed < 0 || cursor < 0 || remaining < 0) {
            throw new IllegalArgumentException("invalid backfill progress");
        }
    }

    public enum BackfillStatus {
        PENDING, RUNNING, PAUSED, COMPLETED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }
}
