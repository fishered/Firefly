package com.firefly.server;

import com.firefly.execution.ExecutionRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Expires stuck work and applies bounded execution-history retention. */
public final class ExecutionMaintenanceWorker implements AutoCloseable {
    private static final Logger log = Logger.getLogger(ExecutionMaintenanceWorker.class.getName());
    private final ExecutionRepository repository;
    private final Clock clock;
    private final ExecutionMaintenanceOptions options;
    private final java.util.function.BooleanSupplier leader;
    private final com.firefly.store.JobRepository jobRepository;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-execution-maintenance");
        thread.setDaemon(false);
        return thread;
    });

    public ExecutionMaintenanceWorker(ExecutionRepository repository, Clock clock, Duration retention) {
        this(repository, clock, new ExecutionMaintenanceOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(5), retention, 200, 500
        ), () -> true, null);
    }

    public ExecutionMaintenanceWorker(
            ExecutionRepository repository,
            Clock clock,
            Duration retention,
            java.util.function.BooleanSupplier leader,
            com.firefly.store.JobRepository jobRepository
    ) {
        this(repository, clock, new ExecutionMaintenanceOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(5), retention, 200, 500
        ), leader, jobRepository);
    }

    public ExecutionMaintenanceWorker(
            ExecutionRepository repository,
            Clock clock,
            ExecutionMaintenanceOptions options,
            java.util.function.BooleanSupplier leader,
            com.firefly.store.JobRepository jobRepository
    ) {
        this.repository = repository;
        this.clock = clock;
        this.options = options;
        this.leader = leader;
        this.jobRepository = jobRepository;
    }

    public void start() {
        timer.scheduleWithFixedDelay(
                this::runSafely,
                options.initialDelay().toMillis(),
                options.interval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void runSafely() {
        try {
            if (!leader.getAsBoolean()) return;
            java.time.Instant now = clock.instant();
            java.util.List<String> expired = repository.expireTimedOutExecutions(
                    now, options.timeoutBatchSize()
            );
            if (jobRepository != null) {
                expired.forEach(executionId -> jobRepository.scheduleExecutionRetry(executionId, now, true));
            }
            repository.deleteCompletedBefore(now.minus(options.retention()), options.cleanupBatchSize());
        } catch (Exception e) {
            log.log(Level.SEVERE, "execution maintenance failed", e);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
