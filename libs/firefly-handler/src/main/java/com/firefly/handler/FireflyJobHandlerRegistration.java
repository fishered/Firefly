package com.firefly.handler;

import com.firefly.idempotency.BusinessIdempotencyStore;
import com.firefly.idempotency.IdempotencyKeyStrategy;
import com.firefly.idempotency.IdempotentJobHandler;

import java.util.Objects;

/**
 * Transport-neutral registration descriptor for business job handlers.
 */
public record FireflyJobHandlerRegistration(
        String handlerName,
        JobHandler handler
) {
    public FireflyJobHandlerRegistration {
        Objects.requireNonNull(handlerName, "handlerName");
        Objects.requireNonNull(handler, "handler");
        if (handlerName.isBlank()) {
            throw new IllegalArgumentException("handlerName must not be blank");
        }
    }

    public static FireflyJobHandlerRegistration of(String handlerName, JobHandler handler) {
        return new FireflyJobHandlerRegistration(handlerName, handler);
    }

    public static FireflyJobHandlerRegistration idempotent(
            String handlerName,
            BusinessIdempotencyStore store,
            IdempotencyKeyStrategy keyStrategy,
            JobHandler handler
    ) {
        return new FireflyJobHandlerRegistration(
                handlerName,
                new IdempotentJobHandler(handler, store, keyStrategy)
        );
    }
}
