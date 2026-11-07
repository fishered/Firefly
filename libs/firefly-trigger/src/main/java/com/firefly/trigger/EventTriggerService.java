package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionIds;
import com.firefly.governance.TenantAdmissionRequest;
import com.firefly.governance.TenantQuotaStore;
import com.firefly.governance.TenantReservationKeys;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Converts an accepted event into the same command/outbox path as a scheduled fire. */
public final class EventTriggerService {
    private final TriggerInbox inbox;
    private final JobRepository jobs;
    private final Clock clock;
    private final TenantQuotaStore tenantQuotaStore;

    public EventTriggerService(TriggerInbox inbox, JobRepository jobs, Clock clock) {
        this(inbox, jobs, clock, null);
    }

    public EventTriggerService(TriggerInbox inbox, JobRepository jobs, Clock clock,
                               TenantQuotaStore tenantQuotaStore) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantQuotaStore = tenantQuotaStore;
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
        if (tenantQuotaStore != null) {
            String triggerReservation = TenantReservationKeys.trigger(root);
            var admission = tenantQuotaStore.tryReserve(new TenantAdmissionRequest(
                    triggerReservation, job.tenantId(), 0, 0, 0, 0, 1
            ), now, Duration.ofSeconds(1));
            tenantQuotaStore.release(triggerReservation, now);
            if (!admission.admitted()) {
                String reason = "tenant trigger rejected: " + admission.reason().name();
                inbox.markFailed(idempotencyKey, now, reason);
                return new TriggerResult(false, false, admission.reason().name());
            }
        }
        com.firefly.store.EnqueueAdmissionResult enqueue;
        try {
            enqueue = jobs.enqueueManualWithAdmission(
                    new ExecutionCommand(root, root, 0, job, now, now, "event", 1L)
            );
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        if (enqueue.accepted() || enqueue.duplicate()) inbox.markProcessed(idempotencyKey, now);
        else inbox.markFailed(idempotencyKey, now, "tenant admission rejected: " + enqueue.reason().name());
        return new TriggerResult(enqueue.accepted() || enqueue.duplicate(), enqueue.duplicate(),
                enqueue.accepted() ? "queued" : enqueue.duplicate() ? "already_queued" : enqueue.reason().name());
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
        com.firefly.store.EnqueueAdmissionResult enqueue;
        try {
            enqueue = jobs.enqueueManualWithAdmission(new ExecutionCommand(
                    root, root, 0, job, now, now, "event-replay", 1L
            ));
        } catch (RuntimeException failure) {
            inbox.markFailed(idempotencyKey, now, failureReason(failure));
            throw failure;
        }
        if (enqueue.accepted() || enqueue.duplicate()) inbox.markProcessed(idempotencyKey, now);
        else inbox.markFailed(idempotencyKey, now, "tenant admission rejected: " + enqueue.reason().name());
        return new TriggerResult(enqueue.accepted() || enqueue.duplicate(), enqueue.duplicate(),
                enqueue.accepted() ? "replayed" : enqueue.duplicate() ? "already_queued" : enqueue.reason().name());
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
