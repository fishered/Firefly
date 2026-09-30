package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionIds;
import com.firefly.execution.ExecutionRepository;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates bounded historical execution through a durable, lease-fenced cursor. */
public final class BackfillCoordinator {
    private final JobRepository jobs;
    private final ExecutionRepository executions;
    private final BackfillOperationStore operations;
    private final Clock clock;
    private final String claimantId;
    private final Duration claimLease;

    public BackfillCoordinator(JobRepository jobs, Clock clock) {
        this(jobs, null, new InMemoryBackfillOperationStore(), clock,
                "backfill-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    public BackfillCoordinator(
            JobRepository jobs,
            ExecutionRepository executions,
            BackfillOperationStore operations,
            Clock clock,
            String claimantId,
            Duration claimLease
    ) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = executions;
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (claimantId == null || claimantId.isBlank()) {
            throw new IllegalArgumentException("claimantId must not be blank");
        }
        this.claimantId = claimantId;
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    public BackfillPreview preview(BackfillRequest request, BackfillOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        return preview(request, options, requireJob(request.jobId()));
    }

    private BackfillPreview preview(BackfillRequest request, BackfillOptions options, JobDefinition job) {
        List<Instant> times = expand(request, options, job);
        int canary = canarySize(times.size(), options.canaryPercent());
        Duration estimated = options.rateLimitPerSecond() == 0
                ? Duration.ZERO
                : Duration.ofNanos(Math.max(0, times.size() - 1L) * options.minimumInterval().toNanos());
        return new BackfillPreview(request.requestId(), times.size(), times, estimated, canary);
    }

    public BackfillProgress start(BackfillRequest request, BackfillOptions options) {
        Optional<BackfillOperation> existing = operations.find(request.requestId());
        if (existing.isPresent()) return existing.get().progress();
        JobDefinition snapshot = requireJob(request.jobId());
        BackfillPreview preview = preview(request, options, snapshot);
        Instant now = clock.instant();
        boolean canaryActive = preview.canaryExecutions() < preview.expanded();
        BackfillProgress.BackfillStatus initialStatus = preview.expanded() == 0
                ? BackfillProgress.BackfillStatus.COMPLETED
                : canaryActive && preview.canaryExecutions() == 0
                ? BackfillProgress.BackfillStatus.PAUSED
                : BackfillProgress.BackfillStatus.PENDING;
        BackfillOperation operation = new BackfillOperation(
                request, options, snapshot, initialStatus, preview.expanded(), 0, 0, 0,
                preview.canaryExecutions(), canaryActive, now, "", null, 0, now, now
        );
        List<BackfillItem> items = new ArrayList<>(preview.expanded());
        for (int index = 0; index < preview.fireTimes().size(); index++) {
            Instant fire = preview.fireTimes().get(index);
            String executionId = ExecutionIds.child(request.rootExecutionId(), "fire:" + fire);
            items.add(new BackfillItem(index, fire, executionId,
                    BackfillItem.BackfillItemStatus.PENDING, ""));
        }
        operations.create(operation, items);
        return operations.find(request.requestId()).orElse(operation).progress();
    }

    public BackfillProgress pause(String requestId) {
        return operations.pause(requestId, clock.instant()).orElseThrow(() -> notFound(requestId)).progress();
    }

    public BackfillProgress resume(String requestId) {
        return operations.resume(requestId, clock.instant()).orElseThrow(() -> notFound(requestId)).progress();
    }

    public BackfillProgress cancel(String requestId) {
        return operations.cancel(requestId, clock.instant()).orElseThrow(() -> notFound(requestId)).progress();
    }

    public BackfillProgress promote(String requestId) {
        return operations.promote(requestId, clock.instant()).orElseThrow(() -> notFound(requestId)).progress();
    }

    public BackfillProgress run(String requestId, int maxExecutions) {
        if (maxExecutions < 1) throw new IllegalArgumentException("maxExecutions must be positive");
        BackfillOperation operation = operations.claim(requestId, clock.instant(), claimantId, claimLease)
                .orElseGet(() -> operations.find(requestId).orElseThrow(() -> notFound(requestId)));
        if (!operation.claimedBy(claimantId)) return operation.progress();
        return process(operation, maxExecutions).progress();
    }

    public List<BackfillProgress> runAvailable(int maxOperations, int maxExecutionsPerOperation) {
        if (maxOperations < 1 || maxExecutionsPerOperation < 1) {
            throw new IllegalArgumentException("backfill worker limits must be positive");
        }
        List<BackfillProgress> progress = new ArrayList<>();
        for (BackfillOperation operation : operations.claimRunnable(
                clock.instant(), claimantId, claimLease, maxOperations)) {
            progress.add(process(operation, maxExecutionsPerOperation).progress());
        }
        return List.copyOf(progress);
    }

    public BackfillProgress progress(String requestId) {
        return operation(requestId).progress();
    }

    public BackfillOperation operation(String requestId) {
        return operations.find(requestId).orElseThrow(() -> notFound(requestId));
    }

    public List<BackfillOperation> list(int limit) {
        return operations.list(limit);
    }

    private BackfillOperation process(BackfillOperation claimed, int requestedLimit) {
        BackfillOperation current = claimed;
        int limit = Math.min(requestedLimit, current.options().batchSize());
        int processed = 0;
        try {
            while (current.claimedBy(claimantId)
                    && current.status() == BackfillProgress.BackfillStatus.RUNNING
                    && processed < limit) {
                Instant now = clock.instant();
                if (current.nextAllowedAt().isAfter(now)) break;
                BackfillItem item = operations.nextItem(current.request().requestId(), claimantId).orElse(null);
                if (item == null) break;
                boolean queued = jobs.enqueueManual(new ExecutionCommand(
                        item.executionId(), item.executionId(), 0, current.definition(), item.fireTime(), now,
                        "backfill", 1L
                ));
                boolean alreadyQueued = !queued && executions != null
                        && executions.findExecution(item.executionId()).isPresent();
                BackfillItem.BackfillItemStatus result = queued || alreadyQueued
                        ? BackfillItem.BackfillItemStatus.DISPATCHED
                        : BackfillItem.BackfillItemStatus.FAILED;
                String error = result == BackfillItem.BackfillItemStatus.FAILED
                        ? "manual execution was not accepted" : "";
                Instant nextAllowedAt = current.options().rateLimitPerSecond() == 0
                        ? now : now.plus(current.options().minimumInterval());
                String requestId = current.request().requestId();
                long expectedVersion = current.version();
                Optional<BackfillOperation> recorded = operations.recordItem(
                        requestId, claimantId, expectedVersion, result, error, nextAllowedAt, now
                );
                BackfillOperation fallback = current;
                current = recorded.orElseGet(() -> operations.find(requestId).orElse(fallback));
                processed++;
                if (current.options().rateLimitPerSecond() > 0) break;
            }
            return current;
        } finally {
            if (current.claimedBy(claimantId)) {
                operations.release(current.request().requestId(), claimantId, current.version(), clock.instant());
                current = operations.find(current.request().requestId()).orElse(current);
            }
        }
    }

    private List<Instant> expand(BackfillRequest request, BackfillOptions options, JobDefinition job) {
        List<Instant> fireTimes = new ArrayList<>();
        Instant cursor = request.fromInclusive().minusNanos(1);
        while (fireTimes.size() < request.maxExecutions()) {
            Instant next = job.schedule().nextAfter(cursor, job.zoneId());
            if (next.isAfter(request.toInclusive())) break;
            if (!next.isBefore(request.fromInclusive())
                    && (options.retryOnlyTimes().isEmpty() || options.retryOnlyTimes().contains(next))) {
                fireTimes.add(next);
            }
            cursor = next;
        }
        if (cursor.isBefore(request.toInclusive()) && fireTimes.size() >= request.maxExecutions()) {
            throw new IllegalArgumentException("backfill exceeds maxExecutions=" + request.maxExecutions());
        }
        return fireTimes.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private JobDefinition requireJob(String jobId) {
        return jobs.find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId))
                .definition();
    }

    private static int canarySize(int size, int percent) {
        if (size == 0 || percent == 0) return 0;
        return Math.min(size, Math.max(1, (int) Math.ceil(size * percent / 100.0)));
    }

    private static IllegalArgumentException notFound(String requestId) {
        return new IllegalArgumentException("backfill run not found: " + requestId);
    }
}
