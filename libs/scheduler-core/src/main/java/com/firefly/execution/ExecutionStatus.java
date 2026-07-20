package com.firefly.execution;

public enum ExecutionStatus {
    DISPATCHING,
    DISPATCHED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    TIMEOUT,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED || this == TIMEOUT || this == CANCELLED;
    }

    public boolean canTransitionTo(ExecutionStatus next) {
        if (next == null) return false;
        if (this == next) return true;
        if (terminal()) return false;
        return switch (next) {
            case DISPATCHING -> false;
            case DISPATCHED -> this == DISPATCHING;
            case RUNNING -> this == DISPATCHING || this == DISPATCHED;
            case SUCCEEDED, PARTIAL, FAILED, TIMEOUT, CANCELLED -> true;
        };
    }
}
