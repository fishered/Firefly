package com.firefly.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Rolls back partially started server components in strict reverse order. */
final class StartupTransaction implements AutoCloseable {
    private final Deque<RollbackAction> rollbacks = new ArrayDeque<>();
    private boolean committed;

    void add(String name, AutoCloseable rollback) {
        if (committed) throw new IllegalStateException("startup transaction is already committed");
        String actionName = Objects.requireNonNull(name, "name");
        rollbacks.removeIf(action -> action.name().equals(actionName));
        rollbacks.push(new RollbackAction(
                actionName,
                Objects.requireNonNull(rollback, "rollback")
        ));
    }

    void commit() {
        committed = true;
        rollbacks.clear();
    }

    @Override
    public void close() {
        if (committed) return;
        RuntimeException failure = null;
        while (!rollbacks.isEmpty()) {
            RollbackAction action = rollbacks.pop();
            try {
                action.rollback().close();
            } catch (Exception e) {
                IllegalStateException wrapped = new IllegalStateException(
                        "failed to roll back startup component: " + action.name(), e
                );
                if (failure == null) failure = wrapped;
                else failure.addSuppressed(wrapped);
            }
        }
        if (failure != null) throw failure;
    }

    private record RollbackAction(String name, AutoCloseable rollback) {
    }
}
