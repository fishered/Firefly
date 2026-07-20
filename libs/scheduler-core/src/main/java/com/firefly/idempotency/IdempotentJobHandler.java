package com.firefly.idempotency;

import com.firefly.domain.ExecutionContext;
import com.firefly.handler.JobHandler;

import java.time.Clock;
import java.util.Objects;

/** Decorates a handler with an atomic business-key claim. */
public final class IdempotentJobHandler implements JobHandler {
    private final JobHandler delegate;
    private final BusinessIdempotencyStore store;
    private final IdempotencyKeyStrategy keyStrategy;
    private final Clock clock;

    public IdempotentJobHandler(
            JobHandler delegate,
            BusinessIdempotencyStore store,
            IdempotencyKeyStrategy keyStrategy
    ) {
        this(delegate, store, keyStrategy, Clock.systemUTC());
    }

    public IdempotentJobHandler(
            JobHandler delegate,
            BusinessIdempotencyStore store,
            IdempotencyKeyStrategy keyStrategy,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.store = Objects.requireNonNull(store, "store");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(ExecutionContext context) throws Exception {
        String key = requireKey(keyStrategy.key(context));
        if (store instanceof FencedBusinessIdempotencyStore fenced) {
            handleFenced(context, key, fenced);
            return;
        }
        BusinessIdempotencyStore.AcquireResult result = store.tryAcquire(key, clock.instant());
        if (result == BusinessIdempotencyStore.AcquireResult.COMPLETED) return;
        if (result == BusinessIdempotencyStore.AcquireResult.IN_PROGRESS) {
            throw new IllegalStateException("idempotent operation is already in progress: " + key);
        }
        try {
            delegate.handle(context);
            store.markCompleted(key, clock.instant());
        } catch (Exception e) {
            store.release(key, clock.instant(), e.getMessage());
            throw e;
        }
    }

    private void handleFenced(
            ExecutionContext context, String key, FencedBusinessIdempotencyStore fenced
    ) throws Exception {
        FencedBusinessIdempotencyStore.Claim claim = fenced.tryAcquireFenced(key, clock.instant());
        if (claim.result() == BusinessIdempotencyStore.AcquireResult.COMPLETED) return;
        if (claim.result() == BusinessIdempotencyStore.AcquireResult.IN_PROGRESS) {
            throw new IllegalStateException("idempotent operation is already in progress: " + key);
        }
        try {
            delegate.handle(context);
            if (!fenced.markCompletedFenced(key, claim.claimToken(), clock.instant())) {
                throw new IllegalStateException("idempotency claim was fenced before completion: " + key);
            }
        } catch (Exception e) {
            fenced.releaseFenced(key, claim.claimToken(), clock.instant(), e.getMessage());
            throw e;
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
        return key;
    }
}
