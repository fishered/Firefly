package io.github.nishi.firefly.engine;

import io.github.nishi.firefly.domain.ConcurrencyPolicy;
import io.github.nishi.firefly.domain.ExecutionContext;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.handler.JobHandler;
import io.github.nishi.firefly.registry.JobHandlerRegistry;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JobDispatcher {
    private static final Logger log = Logger.getLogger(JobDispatcher.class.getName());

    private final JobHandlerRegistry registry;
    private final ExecutorService workerPool;
    private final Clock clock;
    private final ConcurrentMap<String, AtomicInteger> runningCounters = new ConcurrentHashMap<>();

    public JobDispatcher(JobHandlerRegistry registry, ExecutorService workerPool, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void dispatch(JobDefinition definition, Instant scheduledFireTime) {
        AtomicInteger running = runningCounters.computeIfAbsent(definition.id(), ignored -> new AtomicInteger());
        if (definition.concurrencyPolicy() == ConcurrencyPolicy.FORBID && running.get() > 0) {
            log.info(() -> "skip job because previous execution is still running: " + definition.id());
            return;
        }

        JobHandler handler = registry.find(definition.handlerName())
                .orElseThrow(() -> new IllegalStateException("handler not found: " + definition.handlerName()));

        running.incrementAndGet();
        workerPool.submit(() -> {
            Instant actualFireTime = clock.instant();
            ExecutionContext context = new ExecutionContext(
                    definition.id(),
                    definition.handlerName(),
                    scheduledFireTime,
                    actualFireTime,
                    definition.parameters()
            );
            try {
                handler.handle(context);
                log.info(() -> "job succeeded: " + definition.id() + ", scheduled=" + scheduledFireTime);
            } catch (Exception e) {
                log.log(Level.SEVERE, "job failed: " + definition.id() + ", scheduled=" + scheduledFireTime, e);
            } finally {
                running.decrementAndGet();
            }
        });
    }
}

