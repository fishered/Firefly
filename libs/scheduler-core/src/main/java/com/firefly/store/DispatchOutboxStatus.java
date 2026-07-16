package com.firefly.store;

public enum DispatchOutboxStatus {
    PENDING,
    CLAIMED,
    SENT,
    RETRY,
    DONE,
    DEAD
}
