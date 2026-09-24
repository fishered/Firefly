package com.firefly.schedule;

import com.firefly.domain.JobDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Evaluates potentially blocking data-readiness conditions away from the scheduler timer thread.
 *
 * <p>The first evaluation returns {@link ConditionStatus#WAITING} and starts one background check.
 * Subsequent gate polls observe the completed result. Exceptions and timeouts fail closed so an
 * external dependency cannot dispatch work early or stall the scheduler loop.</p>
 */
public final class AsyncDataReadyConditionEvaluator implements SchedulingConditionEvaluator, AutoCloseable {
    private final DataReadyConditionEvaluator delegate;
    private final Clock clock;
    private final Duration timeout;
    private final ExecutorService executor;
    private final ConcurrentHashMap<EvaluationKey, PendingEvaluation> pending = new ConcurrentHashMap<>();

    public AsyncDataReadyConditionEvaluator(
            Collection<? extends DataReadyCondition> conditions,
            Clock clock,
            Duration timeout,
            int concurrency
    ) {
        this.delegate = new DataReadyConditionEvaluator(conditions);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("data-ready timeout must be positive");
        }
        if (concurrency < 1 || concurrency > 128) {
            throw new IllegalArgumentException("data-ready concurrency must be between 1 and 128");
        }
        this.executor = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().daemon().name("firefly-data-ready-", 0).factory());
    }

    @Override
    public ConditionStatus evaluate(JobDefinition definition, Instant businessTime) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(businessTime, "businessTime");
        String declaration = definition.parameters().getOrDefault(
                DataReadyConditionEvaluator.CONDITION_IDS_PARAMETER, ""
        );
        if (declaration.isBlank()) return ConditionStatus.ALLOWED;

        EvaluationKey key = new EvaluationKey(definition.id(), businessTime, definition.parameters().hashCode());
        Instant now = clock.instant();
        PendingEvaluation evaluation = pending.computeIfAbsent(key, ignored -> new PendingEvaluation(
                CompletableFuture.supplyAsync(() -> delegate.evaluate(definition, businessTime), executor),
                now.plus(timeout)
        ));
        if (evaluation.future().isDone()) {
            pending.remove(key, evaluation);
            try {
                return Objects.requireNonNull(evaluation.future().join(), "data-ready result");
            } catch (RuntimeException failure) {
                return ConditionStatus.BLOCKED;
            }
        }
        if (!now.isBefore(evaluation.deadline())) {
            if (pending.remove(key, evaluation)) evaluation.future().cancel(true);
            return ConditionStatus.BLOCKED;
        }
        return ConditionStatus.WAITING;
    }

    int pendingEvaluations() {
        return pending.size();
    }

    @Override
    public void close() {
        pending.values().forEach(value -> value.future().cancel(true));
        pending.clear();
        executor.shutdownNow();
    }

    private record EvaluationKey(String jobId, Instant businessTime, int parametersHash) { }

    private record PendingEvaluation(CompletableFuture<ConditionStatus> future, Instant deadline) { }
}
