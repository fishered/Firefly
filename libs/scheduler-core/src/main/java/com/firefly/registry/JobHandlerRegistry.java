package com.firefly.registry;

import com.firefly.handler.JobHandler;

import java.util.Optional;

public interface JobHandlerRegistry {
    void register(String name, JobHandler handler);

    Optional<JobHandler> find(String name);
}

