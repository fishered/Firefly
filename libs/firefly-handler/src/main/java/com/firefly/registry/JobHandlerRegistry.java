package com.firefly.registry;

import com.firefly.handler.JobHandler;

import java.util.Optional;
import java.util.Set;

public interface JobHandlerRegistry {
    void register(String name, JobHandler handler);

    Optional<JobHandler> find(String name);

    Set<String> names();
}

