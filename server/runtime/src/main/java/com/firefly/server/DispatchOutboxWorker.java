package com.firefly.server;

import com.firefly.engine.JobDispatcher;
import com.firefly.engine.DispatchSubmission;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchType;
import com.firefly.store.JobRepository;
import com.firefly.metrics.SchedulerMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reliably hands committed outbox records to local or remote dispatchers. */
public final class DispatchOutboxWorker implements AutoCloseable {
    private static final Logger log = Logger.getLogger(DispatchOutboxWorker.class.getName());
    private final String nodeId;
    private final JobRepository repository;
    private final JobDispatcher dispatcher;
    private final Clock clock;
    private final Set<DispatchType> dispatchTypes;
    private final SchedulerMetrics metrics;
    private final DispatchOutboxOptions options;
    private final java.util.function.Predicate<com.firefly.engine.ExecutionCommand> dispatchEligibility;
    private volatile java.util.function.BooleanSupplier claimAdmission = () -> true;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-dispatch-outbox");
        thread.setDaemon(false);
        return thread;
    });

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, new SchedulerMetrics());
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, metrics, DispatchOutboxOptions.defaults());
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics,
            DispatchOutboxOptions options
    ) {
        this(nodeId, repository, dispatcher, clock, dispatchTypes, metrics, options, ignored -> true);
    }

    public DispatchOutboxWorker(
            String nodeId,
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            Set<DispatchType> dispatchTypes,
            SchedulerMetrics metrics,
            DispatchOutboxOptions options,
            java.util.function.Predicate<com.firefly.engine.ExecutionCommand> dispatchEligibility
    ) {
        this.nodeId = nodeId;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.dispatchTypes = Set.copyOf(dispatchTypes);
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.dispatchEligibility = java.util.Objects.requireNonNull(dispatchEligibility, "dispatchEligibility");
        if (this.dispatchTypes.isEmpty()) {
            throw new IllegalArgumentException("dispatchTypes must not be empty");
        }
    }

    public void start() {
        timer.scheduleWithFixedDelay(
                this::safeDrain, 0, options.pollInterval().toMillis(), TimeUnit.MILLISECONDS
        );
    }

    public void setClaimAdmission(java.util.function.BooleanSupplier claimAdmission) {
        this.claimAdmission = java.util.Objects.requireNonNull(claimAdmission, "claimAdmission");
    }

    void drain() {
        if (!claimAdmission.getAsBoolean()) return;
        Instant now = clock.instant();
        for (DispatchOutboxRecord record : repository.claimDispatches(
                nodeId, now, options.claimBatchSize(), options.claimDuration(), dispatchTypes
        )) {
            metrics.observeOutboxAge(Duration.between(record.command().dispatchTime(), now));
            if (record.attempt() > options.maxAttempts()) {
                boolean dead = repository.retryClaimedDispatchAfter(
                        record.outboxId(), nodeId, record.attempt(), Duration.ZERO,
                        "maximum delivery attempts exceeded", options.maxAttempts()
                );
                if (dead) metrics.recordOutboxDeliveryExhaustion();
                continue;
            }
            if (!dispatchEligibility.test(record.command())) {
                repository.deferClaimedDispatch(
                        record.outboxId(), nodeId, record.attempt(), options.pollInterval(),
                        "no local executor route on gateway " + nodeId
                );
                continue;
            }
            try {
                DispatchSubmission submission = dispatcher.submit(record.command());
                if (!submission.accepted()) {
                    retry(record, "dispatch not accepted");
                } else if (submission.remote()) {
                    repository.markClaimedDispatchSentFor(
                            record.outboxId(), nodeId, record.attempt(), options.remoteAckTimeout()
                    );
                } else {
                    if (repository.markClaimedDispatchSentFor(
                            record.outboxId(), nodeId, record.attempt(),
                            record.command().definition().timeout()
                    )) {
                        submission.completion().whenComplete((status, error) ->
                        {
                            java.time.Instant completedAt = clock.instant();
                            repository.completeDispatch(record.outboxId(), completedAt);
                            if (status == com.firefly.execution.ExecutionStatus.FAILED || error != null) {
                                repository.scheduleExecutionRetry(record.command().executionId(), completedAt, false);
                            }
                        }
                        );
                    }
                }
            } catch (Exception e) {
                retry(record, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    private void retry(DispatchOutboxRecord record, String error) {
        long delayMillis = Math.min(
                options.maxRetryBackoff().toMillis(),
                Math.multiplyExact(1_000L, 1L << Math.min(record.attempt(), 20))
        );
        repository.retryClaimedDispatchAfter(
                record.outboxId(), nodeId, record.attempt(), Duration.ofMillis(delayMillis),
                error, options.maxAttempts()
        );
    }

    private void safeDrain() {
        try {
            drain();
        } catch (Exception e) {
            log.log(Level.SEVERE, "dispatch outbox drain failed", e);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
