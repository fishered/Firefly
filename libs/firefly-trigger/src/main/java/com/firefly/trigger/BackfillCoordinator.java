package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates bounded backfill work with an explicit cursor. The cursor is
 * intentionally kept behind this small API so a persistent implementation can
 * replace the in-memory state without changing operator semantics.
 */
public final class BackfillCoordinator {
    private final JobRepository jobs;
    private final Clock clock;
    private final Map<String, Run> runs = new HashMap<>();

    public BackfillCoordinator(JobRepository jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized BackfillPreview preview(BackfillRequest request, BackfillOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        List<Instant> times = expand(request, options);
        int canary = canarySize(times.size(), options.canaryPercent());
        Duration estimated = options.rateLimitPerSecond() == 0
                ? Duration.ZERO
                : Duration.ofNanos(Math.max(0, times.size() - 1L) * options.minimumInterval().toNanos());
        return new BackfillPreview(request.requestId(), times.size(), times, estimated, canary);
    }

    public synchronized BackfillProgress start(BackfillRequest request, BackfillOptions options) {
        if (runs.containsKey(request.requestId())) return progress(runs.get(request.requestId()));
        BackfillPreview preview = preview(request, options);
        Run run = new Run(request, options, preview.fireTimes(), preview.canaryExecutions());
        runs.put(request.requestId(), run);
        return progress(run);
    }

    public synchronized BackfillProgress pause(String requestId) {
        Run run = requireRun(requestId);
        if (run.status == BackfillProgress.BackfillStatus.PENDING || run.status == BackfillProgress.BackfillStatus.RUNNING) {
            run.status = BackfillProgress.BackfillStatus.PAUSED;
        }
        return progress(run);
    }

    public synchronized BackfillProgress resume(String requestId) {
        Run run = requireRun(requestId);
        if (run.status == BackfillProgress.BackfillStatus.PAUSED) run.status = BackfillProgress.BackfillStatus.RUNNING;
        return progress(run);
    }

    public synchronized BackfillProgress cancel(String requestId) {
        Run run = requireRun(requestId);
        if (!run.status.terminal()) run.status = BackfillProgress.BackfillStatus.CANCELLED;
        return progress(run);
    }

    public synchronized BackfillProgress promote(String requestId) {
        Run run = requireRun(requestId);
        run.canary = false;
        if (run.status == BackfillProgress.BackfillStatus.PENDING || run.status == BackfillProgress.BackfillStatus.PAUSED) {
            run.status = BackfillProgress.BackfillStatus.RUNNING;
        }
        return progress(run);
    }

    public synchronized BackfillProgress run(String requestId, int maxExecutions) {
        if (maxExecutions < 1) throw new IllegalArgumentException("maxExecutions must be positive");
        Run run = requireRun(requestId);
        if (run.status == BackfillProgress.BackfillStatus.PENDING) run.status = BackfillProgress.BackfillStatus.RUNNING;
        if (run.status != BackfillProgress.BackfillStatus.RUNNING) return progress(run);
        Instant now = clock.instant();
        int limit = Math.min(maxExecutions, run.options.batchSize());
        int releaseLimit = run.canary ? run.canaryExecutions : run.fireTimes.size();
        int processed = 0;
        while (run.cursor < Math.min(run.fireTimes.size(), releaseLimit) && processed < limit) {
            if (run.options.rateLimitPerSecond() > 0 && now.isBefore(run.nextAllowedAt)) break;
            Instant fire = run.fireTimes.get(run.cursor++);
            JobDefinition job = jobs.find(run.request.jobId())
                    .orElseThrow(() -> new IllegalArgumentException("job not found: " + run.request.jobId()))
                    .definition();
            String executionId = run.request.rootExecutionId() + "@" + fire;
            boolean queued = jobs.enqueueManual(new ExecutionCommand(executionId, run.request.rootExecutionId(), 0,
                    job, fire, now, "backfill", 1L));
            if (queued) run.dispatched++; else run.failed++;
            processed++;
            run.nextAllowedAt = now.plus(run.options.minimumInterval());
            now = clock.instant();
        }
        if (run.cursor >= Math.min(run.fireTimes.size(), releaseLimit)) {
            run.status = run.canary && releaseLimit < run.fireTimes.size()
                    ? BackfillProgress.BackfillStatus.PAUSED : BackfillProgress.BackfillStatus.COMPLETED;
        }
        return progress(run);
    }

    public synchronized BackfillProgress progress(String requestId) {
        return progress(requireRun(requestId));
    }

    private List<Instant> expand(BackfillRequest request, BackfillOptions options) {
        JobDefinition job = jobs.find(request.jobId())
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + request.jobId()))
                .definition();
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

    private static int canarySize(int size, int percent) {
        if (size == 0 || percent == 0) return 0;
        return Math.min(size, Math.max(1, (int) Math.ceil(size * percent / 100.0)));
    }

    private Run requireRun(String requestId) {
        Run run = runs.get(requestId);
        if (run == null) throw new IllegalArgumentException("backfill run not found: " + requestId);
        return run;
    }

    private BackfillProgress progress(Run run) {
        int released = run.canary ? run.canaryExecutions : run.fireTimes.size();
        return new BackfillProgress(run.request.requestId(), run.status, run.fireTimes.size(), run.dispatched,
                run.failed, run.cursor, Math.max(0, released - run.cursor), run.canary);
    }

    private static final class Run {
        private final BackfillRequest request;
        private final BackfillOptions options;
        private final List<Instant> fireTimes;
        private final int canaryExecutions;
        private BackfillProgress.BackfillStatus status = BackfillProgress.BackfillStatus.PENDING;
        private int cursor;
        private int dispatched;
        private int failed;
        private boolean canary = true;
        private Instant nextAllowedAt = Instant.MIN;

        private Run(BackfillRequest request, BackfillOptions options, List<Instant> fireTimes, int canaryExecutions) {
            this.request = request; this.options = options; this.fireTimes = fireTimes;
            this.canaryExecutions = canaryExecutions;
            if (canaryExecutions == fireTimes.size()) canary = false;
        }
    }
}
