package io.github.nishi.firefly.engine;

import io.github.nishi.firefly.domain.ConcurrencyPolicy;
import io.github.nishi.firefly.domain.ExecutionContext;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.handler.JobHandler;
import io.github.nishi.firefly.registry.JobHandlerRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
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
        dispatch(new ExecutionCommand(executionId(definition, scheduledFireTime), definition, scheduledFireTime, clock.instant()));
    }

    public void dispatch(ExecutionCommand command) {
        JobDefinition definition = command.definition();
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
                    command.executionId(),
                    definition.id(),
                    definition.handlerName(),
                    command.scheduledFireTime(),
                    command.dispatchTime(),
                    actualFireTime,
                    definition.parameters()
            );
            try {
                handler.handle(context);
                Duration dispatchLag = Duration.between(command.scheduledFireTime(), command.dispatchTime());
                log.info(() -> "job succeeded: " + definition.id()
                        + ", scheduled=" + command.scheduledFireTime()
                        + ", dispatchLag=" + dispatchLag);
            } catch (Exception e) {
                log.log(Level.SEVERE, "job failed: " + definition.id()
                        + ", scheduled=" + command.scheduledFireTime(), e);
            } finally {
                running.decrementAndGet();
            }
        });
    }

    private String executionId(JobDefinition definition, Instant scheduledFireTime) {
        return definition.id() + "@" + scheduledFireTime;
    }
}

