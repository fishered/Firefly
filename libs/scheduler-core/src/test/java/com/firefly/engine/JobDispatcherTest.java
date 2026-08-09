package com.firefly.engine;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.execution.ExecutionMutationResult;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void atomicallyAdmitsOnlyOneForbidExecution() throws Exception {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var callers = Executors.newFixedThreadPool(16);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        try {
            InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
            registry.register("handler", ignored -> releaseHandler.await());
            JobDispatcher dispatcher = new JobDispatcher(registry, workers, fixedClock());
            JobDefinition definition = forbidJob();
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<DispatchSubmission>> attempts = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                int sequence = index;
                attempts.add(callers.submit(() -> {
                    start.await();
                    return dispatcher.submit(command(definition, "forbid-" + sequence));
                }));
            }

            start.countDown();
            List<DispatchSubmission> submissions = new ArrayList<>();
            for (var attempt : attempts) submissions.add(attempt.get(2, TimeUnit.SECONDS));

            assertEquals(1, submissions.stream().filter(DispatchSubmission::accepted).count());
            releaseHandler.countDown();
            DispatchSubmission accepted = submissions.stream()
                    .filter(DispatchSubmission::accepted)
                    .findFirst()
                    .orElseThrow();
            assertEquals(ExecutionStatus.SUCCEEDED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            releaseHandler.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void releasesForbidAdmissionWhenInitialExecutionPersistenceFails() throws Exception {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
            AtomicInteger handled = new AtomicInteger();
            registry.register("handler", ignored -> handled.incrementAndGet());
            ExecutionRepository repository = new FailFirstStartExecutionRepository();
            JobDispatcher dispatcher = new JobDispatcher(
                    registry, workers, fixedClock(), ignored -> com.firefly.executor.RemoteDispatchResult.unavailable(),
                    repository
            );
            JobDefinition definition = forbidJob();

            DispatchSubmission failed = dispatcher.submit(command(definition, "first"));
            assertTrue(failed.accepted());
            assertEquals(ExecutionStatus.FAILED,
                    failed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));

            DispatchSubmission retried = dispatcher.submit(command(definition, "second"));
            assertTrue(retried.accepted());
            assertEquals(ExecutionStatus.SUCCEEDED,
                    retried.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, handled.get());
        } finally {
            workers.shutdownNow();
        }
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static JobDefinition forbidJob() {
        return JobDefinition.builder()
                .id("forbid-job")
                .name("forbid-job")
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(5)))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .build();
    }

    private static ExecutionCommand command(JobDefinition definition, String executionId) {
        return new ExecutionCommand(executionId, definition, NOW, NOW);
    }

    private static final class FailFirstStartExecutionRepository implements ExecutionRepository {
        private final InMemoryExecutionRepository delegate = new InMemoryExecutionRepository();
        private final AtomicBoolean failStart = new AtomicBoolean(true);

        @Override
        public void saveExecution(ExecutionRecord execution) {
            delegate.saveExecution(execution);
        }

        @Override
        public void startExecution(ExecutionRecord execution, Duration timeout) {
            if (failStart.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated start persistence failure");
            }
            delegate.startExecution(execution, timeout);
        }

        @Override
        public void saveTargets(List<ExecutionTargetRecord> targets) {
            delegate.saveTargets(targets);
        }

        @Override
        public ExecutionMutationResult acknowledgeResult(String targetExecutionId, Instant acknowledgedAt) {
            return delegate.acknowledgeResult(targetExecutionId, acknowledgedAt);
        }

        @Override
        public ExecutionMutationResult completeResult(
                String targetExecutionId,
                ExecutionStatus status,
                String errorMessage,
                Instant completedAt
        ) {
            return delegate.completeResult(targetExecutionId, status, errorMessage, completedAt);
        }

        @Override
        public Optional<ExecutionRecord> findExecution(String executionId) {
            return delegate.findExecution(executionId);
        }

        @Override
        public List<ExecutionTargetRecord> listTargets(String executionId) {
            return delegate.listTargets(executionId);
        }

        @Override
        public List<ExecutionRecord> listRecent(int limit) {
            return delegate.listRecent(limit);
        }
    }
}
