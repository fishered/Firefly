package com.firefly.engine;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.JobDefinition;
import com.firefly.handler.JobHandler;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.executor.RemoteExecutionGateway;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.metrics.SchedulerMetrics;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JobDispatcher {
    private static final Logger log = Logger.getLogger(JobDispatcher.class.getName());

    private final JobHandlerRegistry registry;
    private final ExecutorService workerPool;
    private final Clock clock;
    private final RemoteExecutionGateway remoteExecutionGateway;
    private final ExecutionRepository executionRepository;
    private final SchedulerMetrics metrics;
    private final ConcurrentMap<String, AtomicInteger> runningCounters = new ConcurrentHashMap<>();

    public JobDispatcher(JobHandlerRegistry registry, ExecutorService workerPool, Clock clock) {
        this(registry, workerPool, clock, ignored -> RemoteDispatchResult.unavailable(), new InMemoryExecutionRepository());
    }

    public JobDispatcher(
            JobHandlerRegistry registry,
            ExecutorService workerPool,
            Clock clock,
            RemoteExecutionGateway remoteExecutionGateway
    ) {
        this(registry, workerPool, clock, remoteExecutionGateway, new InMemoryExecutionRepository());
    }

    public JobDispatcher(
            JobHandlerRegistry registry,
            ExecutorService workerPool,
            Clock clock,
            RemoteExecutionGateway remoteExecutionGateway,
            ExecutionRepository executionRepository
    ) {
        this(registry, workerPool, clock, remoteExecutionGateway, executionRepository, new SchedulerMetrics());
    }

    public JobDispatcher(
            JobHandlerRegistry registry,
            ExecutorService workerPool,
            Clock clock,
            RemoteExecutionGateway remoteExecutionGateway,
            ExecutionRepository executionRepository,
            SchedulerMetrics metrics
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.remoteExecutionGateway = Objects.requireNonNull(remoteExecutionGateway, "remoteExecutionGateway");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public boolean dispatch(JobDefinition definition, Instant scheduledFireTime) {
        return dispatch(new ExecutionCommand(executionId(definition, scheduledFireTime), definition, scheduledFireTime, clock.instant()));
    }

    public boolean dispatch(ExecutionCommand command) {
        return submit(command).accepted();
    }

    public DispatchSubmission submit(ExecutionCommand command) {
        JobDefinition definition = command.definition();
        boolean remote = isRemote(definition);
        AtomicInteger running = runningCounters.computeIfAbsent(definition.id(), ignored -> new AtomicInteger());
        if (definition.concurrencyPolicy() == ConcurrencyPolicy.FORBID && running.get() > 0) {
            log.info(() -> "skip job because previous execution is still running: " + definition.id());
            return DispatchSubmission.rejected(remote);
        }

        ExecutionContext context = context(command);
        if (remote) {
            try {
                saveExecution(command, ExecutionStatus.DISPATCHING, expectedTargets(definition));
                execute(command, context);
                return DispatchSubmission.acceptedRemote();
            } catch (Exception e) {
                log.log(Level.WARNING, "remote dispatch was not accepted: " + definition.id(), e);
                return DispatchSubmission.rejected(true);
            }
        }

        running.incrementAndGet();
        CompletableFuture<ExecutionStatus> completion = new CompletableFuture<>();
        try {
            workerPool.submit(() -> {
                Instant startedAt = clock.instant();
                saveExecution(command, ExecutionStatus.RUNNING, expectedTargets(definition));
                try {
                    execute(command, context);
                    saveExecution(command, ExecutionStatus.SUCCEEDED, 1);
                    completion.complete(ExecutionStatus.SUCCEEDED);
                    Duration dispatchLag = Duration.between(command.scheduledFireTime(), command.dispatchTime());
                    log.info(() -> "job succeeded: " + definition.id()
                            + ", scheduled=" + command.scheduledFireTime()
                            + ", dispatchLag=" + dispatchLag);
                } catch (Exception e) {
                    saveExecution(command, ExecutionStatus.FAILED, expectedTargets(definition));
                    completion.complete(ExecutionStatus.FAILED);
                    log.log(Level.SEVERE, "job failed: " + definition.id()
                            + ", scheduled=" + command.scheduledFireTime(), e);
                } finally {
                    metrics.observeExecutionDuration(Duration.between(startedAt, clock.instant()));
                    running.decrementAndGet();
                }
            });
            return new DispatchSubmission(true, false, completion);
        } catch (RuntimeException e) {
            running.decrementAndGet();
            completion.completeExceptionally(e);
            return DispatchSubmission.rejected(false);
        }
    }

    private ExecutionContext context(ExecutionCommand command) {
        JobDefinition definition = command.definition();
        return new ExecutionContext(
                command.executionId(), command.rootExecutionId(), command.runAttempt(),
                definition.id(), definition.handlerName(), command.scheduledFireTime(),
                command.dispatchTime(), clock.instant(), definition.parameters()
        );
    }

    private int expectedTargets(JobDefinition definition) {
        return switch (definition.dispatchMode()) {
            case UNICAST -> 1;
            case BROADCAST -> 0;
            case SHARDING -> definition.shardCount();
        };
    }

    private void saveExecution(ExecutionCommand command, ExecutionStatus status, int expectedTargets) {
        Instant now = clock.instant();
        JobDefinition definition = command.definition();
        ExecutionRecord record = new ExecutionRecord(
                command.executionId(), command.rootExecutionId(), command.runAttempt(), definition.id(),
                command.scheduledFireTime(), command.dispatchTime(),
                definition.dispatchMode(), definition.completionPolicy(), status, expectedTargets,
                status == ExecutionStatus.SUCCEEDED ? 1 : 0, command.ownerNodeId(), command.fencingToken(), now, now
        );
        if (status == ExecutionStatus.DISPATCHING || status == ExecutionStatus.RUNNING) {
            executionRepository.startExecution(record, definition.timeout());
        } else {
            executionRepository.saveExecution(record);
        }
    }

    private void execute(ExecutionCommand command, ExecutionContext context) throws Exception {
        JobDefinition definition = command.definition();
        if (isRemote(definition)) {
            String executorName = remoteExecutorName(definition);
            RemoteDispatchResult result = remoteExecutionGateway.dispatch(new RemoteDispatchRequest(
                    executorName,
                    remoteHandlerName(definition),
                    context,
                    definition.dispatchMode(),
                    definition.routingStrategy(),
                    definition.completionPolicy(),
                    definition.shardCount(),
                    definition.routingKey().isBlank() ? context.executionId() : definition.routingKey(),
                    command.ownerNodeId(),
                    command.fencingToken(),
                    command.rootExecutionId(),
                    command.runAttempt(),
                    definition.retryScope()
            ));
            if (!result.accepted()) {
                throw new IllegalStateException("no online executor instance for " + executorName);
            }
            return;
        }
        JobHandler handler = registry.find(definition.handlerName())
                .orElseThrow(() -> new IllegalStateException("handler not found: " + definition.handlerName()));
        handler.handle(context);
    }

    private boolean isRemote(JobDefinition definition) {
        return definition.remote();
    }

    private String remoteExecutorName(JobDefinition definition) {
        return definition.destination().executorName();
    }

    private String remoteHandlerName(JobDefinition definition) {
        return definition.businessHandlerName();
    }

    private String executionId(JobDefinition definition, Instant scheduledFireTime) {
        return definition.id() + "@" + scheduledFireTime;
    }
}

