package io.github.nishi.firefly.integration;

import io.github.nishi.firefly.catalog.InMemorySchedulerCatalog;
import io.github.nishi.firefly.catalog.SchedulerCatalog;
import io.github.nishi.firefly.domain.ExecutorDefinition;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.domain.JobGroupDefinition;
import io.github.nishi.firefly.engine.JobDispatcher;
import io.github.nishi.firefly.engine.SchedulerEngine;
import io.github.nishi.firefly.handler.JobHandler;
import io.github.nishi.firefly.registry.InMemoryJobHandlerRegistry;
import io.github.nishi.firefly.registry.JobHandlerRegistry;
import io.github.nishi.firefly.store.InMemoryJobRepository;
import io.github.nishi.firefly.store.JobRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small embedded facade for traditional Java services and non-Spring containers.
 */
public final class FireflyScheduler implements AutoCloseable {
    public static final String DEFAULT_EXECUTOR_NAME = "embedded";
    public static final String DEFAULT_GROUP_ID = "default";

    private final FireflyOptions options;
    private final SchedulerCatalog catalog;
    private final JobRepository repository;
    private final JobHandlerRegistry handlerRegistry;
    private final ExecutorService workerPool;
    private final SchedulerEngine engine;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FireflyScheduler(FireflyOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.catalog = new InMemorySchedulerCatalog();
        this.repository = new InMemoryJobRepository();
        this.handlerRegistry = new InMemoryJobHandlerRegistry();
        this.workerPool = Executors.newFixedThreadPool(
                options.workerThreads(),
                namedThreadFactory(options.workerThreadNamePrefix())
        );
        JobDispatcher dispatcher = new JobDispatcher(handlerRegistry, workerPool, options.clock());
        this.engine = new SchedulerEngine(repository, dispatcher, options.clock());
        registerEmbeddedDefaults();
    }

    public static FireflyScheduler create() {
        return create(FireflyOptions.builder().build());
    }

    public static FireflyScheduler create(FireflyOptions options) {
        return new FireflyScheduler(options);
    }

    /**
     * Registers a handler by name so existing services can keep handler creation in their own container.
     */
    public FireflyScheduler registerHandler(String name, JobHandler handler) {
        ensureOpen();
        handlerRegistry.register(name, handler);
        return this;
    }

    /**
     * Registers both the handler and its initial schedule in one call.
     */
    public FireflyScheduler register(FireflyJobRegistration registration) {
        ensureOpen();
        registerHandler(registration.definition().handlerName(), registration.handler());
        schedule(registration.definition());
        return this;
    }

    /**
     * Saves a job using the schedule's next fire time in the job's own zone.
     */
    public FireflyScheduler schedule(JobDefinition definition) {
        ensureOpen();
        Instant initialNextFireTime = definition.schedule().nextAfter(options.clock().instant(), definition.zoneId());
        catalog.saveJob(definition);
        repository.save(definition, initialNextFireTime);
        return this;
    }

    public SchedulerCatalog catalog() {
        return catalog;
    }

    public void start() {
        ensureOpen();
        engine.start();
    }

    public JobRepository repository() {
        return repository;
    }

    public JobHandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    public SchedulerEngine engine() {
        return engine;
    }

    /**
     * Stops the timer and worker threads owned by this embedded scheduler.
     */
    public void stop() {
        if (closed.compareAndSet(false, true)) {
            engine.stop();
            workerPool.shutdownNow();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("firefly scheduler is closed");
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private void registerEmbeddedDefaults() {
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name(DEFAULT_EXECUTOR_NAME)
                .description("Embedded in-process executor")
                .build());
        catalog.saveJobGroup(JobGroupDefinition.builder()
                .id(DEFAULT_GROUP_ID)
                .name("Default")
                .executorName(DEFAULT_EXECUTOR_NAME)
                .build());
    }
}
