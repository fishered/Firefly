package com.firefly.executor;

/**
 * Atomic admission boundary used after routing against a resource snapshot.
 * Implementations must compare and reserve capacity in one operation.
 */
@FunctionalInterface
public interface ExecutorResourceReservation {
    boolean tryReserve(ExecutorResourceSnapshot snapshot, ExecutorResourceRequirement requirement);
}
