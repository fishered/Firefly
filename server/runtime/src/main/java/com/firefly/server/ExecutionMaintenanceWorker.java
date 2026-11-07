package com.firefly.server;

import com.firefly.execution.ExecutionRepository;
import com.firefly.lifecycle.ManagedWorker;

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
    private final com.firefly.governance.TenantQuotaStore tenantQuotaStore;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-execution-maintenance");
        thread.setDaemon(false);
        return thread;
    });

    public ExecutionMaintenanceWorker(ExecutionRepository repository, Clock clock, Duration retention) {
        this(repository, clock, new ExecutionMaintenanceOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(5), retention, 200, 500
        ), () -> true, null, null);
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
        ), leader, jobRepository, null);
    }

    public ExecutionMaintenanceWorker(
            ExecutionRepository repository,
            Clock clock,
            ExecutionMaintenanceOptions options,
            java.util.function.BooleanSupplier leader,
            com.firefly.store.JobRepository jobRepository
    ) {
        this(repository, clock, options, leader, jobRepository, null);
    }

    public ExecutionMaintenanceWorker(
            ExecutionRepository repository,
            Clock clock,
            ExecutionMaintenanceOptions options,
            java.util.function.BooleanSupplier leader,
            com.firefly.store.JobRepository jobRepository,
            com.firefly.governance.TenantQuotaStore tenantQuotaStore
    ) {
        this.repository = repository;
        this.clock = clock;
        this.options = options;
        this.leader = leader;
        this.jobRepository = jobRepository;
        this.tenantQuotaStore = tenantQuotaStore;
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
            runOnce();
        } catch (Exception e) {
            log.log(Level.SEVERE, "execution maintenance failed", e);
        }
    }

    void runOnce() {
        if (!leader.getAsBoolean()) return;
        java.time.Instant now = clock.instant();
        java.util.List<String> expired = repository.expireTimedOutExecutions(
                now, options.timeoutBatchSize()
        );
        if (tenantQuotaStore != null) {
            expired.forEach(executionId -> {
                tenantQuotaStore.release(
                        com.firefly.governance.TenantReservationKeys.running(executionId), now
                );
                tenantQuotaStore.release(
                        com.firefly.governance.TenantReservationKeys.backlog(executionId), now
                );
            });
            tenantQuotaStore.reclaimExpired(now, options.timeoutBatchSize());
            tenantQuotaStore.reconcileBacklogReservations(
                    now, java.time.Duration.ofMinutes(1), options.timeoutBatchSize()
            );
        }
        // A retry is a new backlog admission. Release the terminal attempt first so a
        // maxBacklog=1 tenant can retry without being rejected by its own completed slot.
        if (jobRepository != null) {
            expired.forEach(executionId -> jobRepository.scheduleExecutionRetry(executionId, now, true));
        }
        repository.deleteCompletedBefore(now.minus(options.retention()), options.cleanupBatchSize());
    }

    @Override
    public void close() {
        ManagedWorker.stop(timer, java.time.Duration.ofSeconds(5), () -> { }, log);
    }
}
