package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionIds;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Accepts bursty events and releases one idempotent execution per window. */
public final class EventCoalescingService {
    public static final String LATEST_PAYLOAD_PARAMETER = "firefly.event.latestPayload";
    public static final String EVENT_COUNT_PARAMETER = "firefly.event.count";
    public static final String AGGREGATION_KEY_PARAMETER = "firefly.event.aggregationKey";

    private final TriggerInbox inbox;
    private final JobRepository jobs;
    private final Clock clock;
    private final EventAggregationStore aggregationStore;
    private final String claimantId;
    private final Duration claimLease;
    private final int claimBatchSize;

    public EventCoalescingService(TriggerInbox inbox, JobRepository jobs, Clock clock, EventCoalescer coalescer) {
        this(inbox, jobs, clock, coalescer, "event-worker-" + UUID.randomUUID(),
                Duration.ofSeconds(30), 100);
    }

    public EventCoalescingService(
            TriggerInbox inbox,
            JobRepository jobs,
            Clock clock,
            EventAggregationStore aggregationStore,
            String claimantId,
            Duration claimLease,
            int claimBatchSize
    ) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.aggregationStore = Objects.requireNonNull(aggregationStore, "aggregationStore");
        if (claimantId == null || claimantId.isBlank()) {
            throw new IllegalArgumentException("claimantId must not be blank");
        }
        this.claimantId = claimantId;
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
        if (claimBatchSize < 1) throw new IllegalArgumentException("claimBatchSize must be positive");
        this.claimBatchSize = claimBatchSize;
    }

    public AcceptResult accept(
            String jobId, String eventId, String eventType, String idempotencyKey,
            String payload, EventAggregationPolicy policy
    ) {
        Instant now = clock.instant();
        EventTrigger trigger = new EventTrigger(eventId, eventType, idempotencyKey, payload, now,
                EventTrigger.TriggerStatus.RECEIVED, null);
        boolean accepted = inbox.receive(trigger);
        if (!accepted) {
            trigger = inbox.findByIdempotencyKey(idempotencyKey).orElse(trigger);
            if (trigger.status() != EventTrigger.TriggerStatus.RECEIVED) {
                return new AcceptResult(false, true, false, "duplicate");
            }
        }
        var released = aggregationStore.add(
                jobId, trigger, policy, now, claimantId, claimLease
        );
        if (released.isEmpty()) {
            return new AcceptResult(accepted, !accepted, false,
                    accepted ? "debounced" : "duplicate_pending");
        }
        boolean queued = release(released.get());
        return new AcceptResult(accepted, !accepted, true, queued ? "queued" : "retry_pending");
    }

    public FlushResult flushDue() {
        Instant now = clock.instant();
        int released = 0;
        int queued = 0;
        for (AggregatedEvent event : aggregationStore.claimDue(
                now, claimantId, claimLease, claimBatchSize
        )) {
            released++;
            if (release(event)) queued++;
        }
        return new FlushResult(released, queued);
    }

    private boolean release(AggregatedEvent aggregate) {
        try {
            JobDefinition original = jobs.find(aggregate.jobId())
                    .orElseThrow(() -> new IllegalArgumentException("job not found: " + aggregate.jobId()))
                    .definition();
            Map<String, String> parameters = new HashMap<>(original.parameters());
            parameters.put(LATEST_PAYLOAD_PARAMETER, aggregate.latestPayload());
            parameters.put(EVENT_COUNT_PARAMETER, Integer.toString(aggregate.eventCount()));
            parameters.put(AGGREGATION_KEY_PARAMETER, aggregate.aggregationKey());
            JobDefinition definition = original.withParameters(parameters);
            String executionId = ExecutionIds.child(definition.id(),
                    "event-aggregate:" + aggregate.aggregateId());
            jobs.enqueueManual(new ExecutionCommand(executionId, executionId, 0, definition,
                    aggregate.firstReceivedAt(), clock.instant(), "event", 1L));
            Instant processedAt = clock.instant();
            for (String key : aggregate.idempotencyKeys()) {
                inbox.markProcessed(key, processedAt);
            }
            return aggregationStore.complete(aggregate.aggregateId(), claimantId);
        } catch (RuntimeException failure) {
            aggregationStore.release(aggregate.aggregateId(), claimantId, clock.instant());
            return false;
        }
    }

    public record AcceptResult(boolean accepted, boolean duplicate, boolean released, String status) { }
    public record FlushResult(int released, int queued) { }
}
