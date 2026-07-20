package com.firefly.idempotency;

import com.firefly.domain.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotentJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void executesCompletedBusinessKeyOnlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotentJobHandler handler = new IdempotentJobHandler(
                ignored -> calls.incrementAndGet(), new InMemoryBusinessIdempotencyStore(),
                IdempotencyKeyStrategy.rootExecutionId(), CLOCK
        );

        handler.handle(context("execution-1", "root-1", Map.of()));
        handler.handle(context("execution-2", "root-1", Map.of()));

        assertEquals(1, calls.get());
    }

    @Test
    void rejectsAConcurrentClaim() {
        InMemoryBusinessIdempotencyStore store = new InMemoryBusinessIdempotencyStore();
        store.tryAcquire("root-1", NOW);
        IdempotentJobHandler handler = new IdempotentJobHandler(
                ignored -> { }, store, IdempotencyKeyStrategy.rootExecutionId(), CLOCK
        );

        assertThrows(IllegalStateException.class,
                () -> handler.handle(context("execution-1", "root-1", Map.of())));
    }

    @Test
    void releasesFailedClaimSoRetryCanRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotentJobHandler handler = new IdempotentJobHandler(
                ignored -> {
                    if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary");
                },
                new InMemoryBusinessIdempotencyStore(),
                IdempotencyKeyStrategy.rootExecutionId(), CLOCK
        );

        assertThrows(IllegalStateException.class,
                () -> handler.handle(context("execution-1", "root-1", Map.of())));
        handler.handle(context("execution-2", "root-1", Map.of()));

        assertEquals(2, calls.get());
    }

    @Test
    void isolatesShardKeys() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotentJobHandler handler = new IdempotentJobHandler(
                ignored -> calls.incrementAndGet(), new InMemoryBusinessIdempotencyStore(),
                IdempotencyKeyStrategy.rootAndShard(), CLOCK
        );

        handler.handle(context("execution-1", "root-1", Map.of("firefly.shard.index", "0")));
        handler.handle(context("execution-2", "root-1", Map.of("firefly.shard.index", "1")));
        handler.handle(context("execution-3", "root-1", Map.of("firefly.shard.index", "0")));

        assertEquals(2, calls.get());
    }

    private ExecutionContext context(String executionId, String rootExecutionId, Map<String, String> parameters) {
        return new ExecutionContext(
                executionId, rootExecutionId, 0, "job-1", "handler-1",
                NOW, NOW, NOW, parameters
        );
    }
}
