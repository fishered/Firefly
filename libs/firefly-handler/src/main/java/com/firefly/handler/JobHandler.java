package com.firefly.handler;

import com.firefly.domain.ExecutionContext;

@FunctionalInterface
public interface JobHandler {
    void handle(ExecutionContext context) throws Exception;
}

