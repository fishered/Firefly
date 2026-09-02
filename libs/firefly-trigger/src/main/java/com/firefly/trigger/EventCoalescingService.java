package com.firefly.trigger;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Accepts bursty events and releases one idempotent execution per window. */
public final class EventCoalescingService {
    public static final String LATEST_PAYLOAD_PARAMETER = "firefly.event.latestPayload";
    public static final String EVENT_COUNT_PARAMETER = "firefly.event.count";
    public static final String AGGREGATION_KEY_PARAMETER = "firefly.event.aggregationKey";

    private final TriggerInbox inbox;
    private final JobRepository jobs;
    private final Clock clock;
    private final EventCoalescer coalescer;

    public EventCoalescingService(TriggerInbox inbox, JobRepository jobs, Clock clock, EventCoalescer coalescer) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
    }

    public AcceptResult accept(
            String jobId, String eventId, String eventType, String idempotencyKey,
            String payload, EventAggregationPolicy policy
    ) {
        Instant now = clock.instant();
        EventTrigger trigger = new EventTrigger(eventId, eventType, idempotencyKey, payload, now,
                EventTrigger.TriggerStatus.RECEIVED, null);
        if (!inbox.receive(trigger)) return new AcceptResult(false, true, false, "duplicate");
        var released = coalescer.add(jobId, trigger, policy, now);
        if (released.isEmpty()) return new AcceptResult(true, false, false, "debounced");
        boolean queued = release(released.get());
        return new AcceptResult(queued, false, true, queued ? "queued" : "failed");
    }

    public FlushResult flushDue() {
        Instant now = clock.instant();
        int released = 0;
        int queued = 0;
        for (AggregatedEvent event : coalescer.flushDue(now)) {
            released++;
            if (release(event)) queued++;
        }
        return new FlushResult(released, queued);
    }

    private boolean release(AggregatedEvent aggregate) {
        JobDefinition original = jobs.find(aggregate.jobId())
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + aggregate.jobId()))
                .definition();
        Map<String, String> parameters = new HashMap<>(original.parameters());
        parameters.put(LATEST_PAYLOAD_PARAMETER, aggregate.latestPayload());
        parameters.put(EVENT_COUNT_PARAMETER, Integer.toString(aggregate.eventCount()));
        parameters.put(AGGREGATION_KEY_PARAMETER, aggregate.aggregationKey());
        JobDefinition definition = original.withParameters(parameters);
        String executionId = definition.id() + "@event:" + aggregate.aggregationKey() + ":" + aggregate.firstReceivedAt();
        boolean queued = jobs.enqueueManual(new ExecutionCommand(executionId, executionId, 0, definition,
                aggregate.firstReceivedAt(), clock.instant(), "event", 1L));
        Instant processedAt = clock.instant();
        for (String key : aggregate.idempotencyKeys()) {
            if (queued) inbox.markProcessed(key, processedAt);
            else inbox.markFailed(key, processedAt);
        }
        return queued;
    }

    public record AcceptResult(boolean accepted, boolean duplicate, boolean released, String status) { }
    public record FlushResult(int released, int queued) { }
}
