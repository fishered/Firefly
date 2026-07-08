package io.github.nishi.firefly.domain;

/**
 * Describes how the scheduler can reach an executor without binding core code to a network library.
 */
public enum ExecutorProtocol {
    EMBEDDED,
    HTTP,
    TCP
}
