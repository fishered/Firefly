package io.github.nishi.firefly.registry;

import io.github.nishi.firefly.handler.JobHandler;

import java.util.Optional;

public interface JobHandlerRegistry {
    void register(String name, JobHandler handler);

    Optional<JobHandler> find(String name);
}

