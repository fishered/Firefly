package com.firefly.executor.netty;

/**
 * Controls whether an executor registration may create its definition when it
 * does not already exist in the scheduler catalog.
 */
public enum ExecutorDefinitionRegistrationPolicy {
    /** Preserve the legacy Gateway behavior controlled by server configuration. */
    ALLOW_AUTO_CREATE,

    /** Reject registration when the scheduler catalog has no matching definition. */
    REQUIRE_EXISTING
}
