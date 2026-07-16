package com.firefly.executor.netty;

import com.firefly.handler.JobHandler;

import java.util.Objects;

/**
 * Registers a business handler for the Netty executor client.
 */
public record NettyJobHandlerRegistration(
        String handlerName,
        JobHandler handler
) {
    public NettyJobHandlerRegistration {
        Objects.requireNonNull(handlerName, "handlerName");
        Objects.requireNonNull(handler, "handler");
        if (handlerName.isBlank()) {
            throw new IllegalArgumentException("handlerName must not be blank");
        }
    }

    public static NettyJobHandlerRegistration of(String handlerName, JobHandler handler) {
        return new NettyJobHandlerRegistration(handlerName, handler);
    }
}
