package com.firefly.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable claim boundary for event aggregation windows. */
public interface EventAggregationStore {
    Optional<AggregatedEvent> add(
            String jobId,
            EventTrigger trigger,
            EventAggregationPolicy policy,
            Instant now,
            String claimantId,
            Duration claimLease
    );

    List<AggregatedEvent> claimDue(
            Instant now, String claimantId, Duration claimLease, int limit
    );

    boolean complete(String aggregateId, String claimantId);

    boolean release(String aggregateId, String claimantId, Instant now);
}
