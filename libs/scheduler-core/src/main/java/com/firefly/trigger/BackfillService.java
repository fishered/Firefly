package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Expands a bounded interval and enqueues commands through the normal outbox. */
public final class BackfillService {
    private final JobRepository jobs;
    private final Clock clock;
    public BackfillService(JobRepository jobs, Clock clock) { this.jobs = Objects.requireNonNull(jobs, "jobs"); this.clock = Objects.requireNonNull(clock, "clock"); }

    public Result submit(BackfillRequest request) {
        JobDefinition job = jobs.find(request.jobId()).orElseThrow(() -> new IllegalArgumentException("job not found: " + request.jobId())).definition();
        List<Instant> fireTimes = new ArrayList<>();
        Instant cursor = request.fromInclusive().minusNanos(1);
        while (fireTimes.size() < request.maxExecutions()) {
            Instant next = job.schedule().nextAfter(cursor, job.zoneId());
            if (next.isAfter(request.toInclusive())) break;
            if (!next.isBefore(request.fromInclusive())) fireTimes.add(next);
            cursor = next;
        }
        if (cursor.isBefore(request.toInclusive()) && fireTimes.size() >= request.maxExecutions()) {
            throw new IllegalArgumentException("backfill exceeds maxExecutions=" + request.maxExecutions());
        }
        int queued = 0;
        for (Instant fire : fireTimes) {
            String id = request.rootExecutionId() + "@" + fire;
            if (jobs.enqueueManual(new ExecutionCommand(id, request.rootExecutionId(), 0, job, fire, clock.instant(), "backfill", 1L))) queued++;
        }
        return new Result(request.requestId(), fireTimes.size(), queued);
    }
    public record Result(String requestId, int expanded, int queued) { }
}
