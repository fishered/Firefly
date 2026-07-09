package com.firefly.integration;

import com.firefly.domain.JobDefinition;
import com.firefly.handler.JobHandler;

import java.util.Objects;

/**
 * Binds a job definition to the handler that should run when the job fires.
 */
public record FireflyJobRegistration(
        JobDefinition definition,
        JobHandler handler
) {
    public FireflyJobRegistration {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
    }

    public static FireflyJobRegistration of(JobDefinition definition, JobHandler handler) {
        return new FireflyJobRegistration(definition, handler);
    }
}
