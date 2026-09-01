package com.firefly.store;

/** Identifies which node role is allowed to claim an outbox dispatch. */
public enum DispatchType {
    LOCAL,
    REMOTE
}
