package com.firefly.executor.netty;

import com.firefly.handler.JobHandler;
import com.firefly.idempotency.BusinessIdempotencyStore;
import com.firefly.idempotency.IdempotencyKeyStrategy;
import com.firefly.idempotency.IdempotentJobHandler;

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

    public static NettyJobHandlerRegistration idempotent(
            String handlerName,
            BusinessIdempotencyStore store,
            IdempotencyKeyStrategy keyStrategy,
            JobHandler handler
    ) {
        return new NettyJobHandlerRegistration(
                handlerName,
                new IdempotentJobHandler(handler, store, keyStrategy)
        );
    }
}
