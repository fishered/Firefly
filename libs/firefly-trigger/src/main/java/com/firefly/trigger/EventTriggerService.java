package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionIds;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Converts an accepted event into the same command/outbox path as a scheduled fire. */
public final class EventTriggerService {
    private final TriggerInbox inbox;
    private final JobRepository jobs;
    private final Clock clock;

    public EventTriggerService(TriggerInbox inbox, JobRepository jobs, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox"); this.jobs = Objects.requireNonNull(jobs, "jobs"); this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TriggerResult accept(String jobId, String eventId, String eventType, String idempotencyKey, String payload) {
        Instant now = clock.instant();
        EventTrigger event = new EventTrigger(eventId, eventType, idempotencyKey, payload, now,
                EventTrigger.TriggerStatus.RECEIVED, null);
        if (!inbox.receive(jobId, event)) return new TriggerResult(false, true, "duplicate");
        JobDefinition job;
        try {
            job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId)).definition();
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        String root = ExecutionIds.child(job.id(), "event:" + idempotencyKey);
        boolean queued;
        try {
            queued = jobs.enqueueManual(new ExecutionCommand(root, root, 0, job, now, now, "event", 1L));
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        boolean alreadyQueued = !queued && jobs.findDispatch(root).isPresent();
        if (queued || alreadyQueued) inbox.markProcessed(idempotencyKey, now);
        else inbox.markFailed(idempotencyKey, now, "dispatch was not accepted");
        return new TriggerResult(queued || alreadyQueued, alreadyQueued, queued ? "queued" : "already_queued");
    }

    public TriggerResult replay(String idempotencyKey) {
        Instant now = clock.instant();
        FailedEventTrigger failed = inbox.claimFailedForReplay(idempotencyKey, now).orElse(null);
        if (failed == null) return new TriggerResult(false, true, "not_failed_or_already_claimed");
        EventTrigger event = failed.event();
        JobDefinition job;
        try {
            String jobId = failed.jobId();
            job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId)).definition();
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        String root = ExecutionIds.child(job.id(), "event:" + idempotencyKey);
        boolean queued;
        try {
            queued = jobs.enqueueManual(new ExecutionCommand(
                    root, root, 0, job, now, now, "event-replay", 1L
            ));
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        boolean alreadyQueued = !queued && jobs.findDispatch(root).isPresent();
        if (queued || alreadyQueued) inbox.markProcessed(idempotencyKey, now);
        else inbox.markFailed(idempotencyKey, now, "dispatch replay was not accepted");
        return new TriggerResult(queued || alreadyQueued, alreadyQueued, queued ? "replayed" : "already_queued");
    }

    public TriggerResult acceptSigned(String jobId, String eventId, String eventType, String idempotencyKey,
                                      String payload, Instant signedAt, String signature,
                                      EventSignatureVerifier verifier) {
        if (!Objects.requireNonNull(verifier, "verifier").verify(eventId, eventType, idempotencyKey,
                payload, signedAt, signature)) {
            throw new SecurityException("invalid or expired event signature");
        }
        return accept(jobId, eventId, eventType, idempotencyKey, payload);
    }

    private String failureReason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    public record TriggerResult(boolean accepted, boolean duplicate, String status) { }
}
