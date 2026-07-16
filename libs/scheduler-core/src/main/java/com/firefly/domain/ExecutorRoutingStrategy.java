package com.firefly.domain;

/** Determines which eligible instance receives a unicast or shard dispatch. */
public enum ExecutorRoutingStrategy {
    ROUND_ROBIN,
    RANDOM,
    CONSISTENT_HASH
}
