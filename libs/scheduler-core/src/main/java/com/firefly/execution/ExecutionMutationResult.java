package com.firefly.execution;

/** Distinguishes a new state transition from an idempotent replay. */
public enum ExecutionMutationResult {
    APPLIED,
    ALREADY_APPLIED,
    REJECTED;

    public boolean accepted() {
        return this != REJECTED;
    }
}
