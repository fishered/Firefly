package com.firefly.domain;

/** Describes how child execution results are aggregated for multi-target dispatch. */
public enum ExecutorCompletionPolicy {
    ALL_SUCCESS,
    ANY_SUCCESS,
    QUORUM
}
