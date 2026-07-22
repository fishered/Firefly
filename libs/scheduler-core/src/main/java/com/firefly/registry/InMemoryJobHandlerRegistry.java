package com.firefly.registry;

import com.firefly.handler.JobHandler;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryJobHandlerRegistry implements JobHandlerRegistry {
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(String name, JobHandler handler) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        if (name.isBlank() || name.contains(",")) {
            throw new IllegalArgumentException("handler name must not be blank or contain ','");
        }
        handlers.put(name, handler);
    }

    @Override
    public Optional<JobHandler> find(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    @Override
    public java.util.Set<String> names() {
        return java.util.Set.copyOf(handlers.keySet());
    }
}

