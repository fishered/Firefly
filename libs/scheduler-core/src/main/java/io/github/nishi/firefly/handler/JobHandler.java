package io.github.nishi.firefly.handler;

import io.github.nishi.firefly.domain.ExecutionContext;

@FunctionalInterface
public interface JobHandler {
    void handle(ExecutionContext context) throws Exception;
}

