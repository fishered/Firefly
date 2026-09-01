package com.firefly.domain;

/** Determines how many executor instances receive one scheduled execution. */
public enum ExecutorDispatchMode {
    UNICAST,
    BROADCAST,
    SHARDING
}
