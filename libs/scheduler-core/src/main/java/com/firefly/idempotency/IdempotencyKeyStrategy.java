package com.firefly.idempotency;

import com.firefly.domain.ExecutionContext;

@FunctionalInterface
public interface IdempotencyKeyStrategy {
    String key(ExecutionContext context);

    static IdempotencyKeyStrategy rootExecutionId() {
        return ExecutionContext::rootExecutionId;
    }

    static IdempotencyKeyStrategy rootAndShard() {
        return context -> context.rootExecutionId() + ":shard:"
                + context.parameters().getOrDefault("firefly.shard.index", "0");
    }

    static IdempotencyKeyStrategy parameter(String name) {
        return context -> {
            String value = context.parameters().get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing idempotency parameter: " + name);
            }
            return context.jobId() + ":" + value;
        };
    }
}
