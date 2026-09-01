package com.firefly.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Ownership boundary for claim, acknowledgement, retry and cancellation of dispatches. */
public interface DispatchOutboxStore {
    List<DispatchOutboxRecord> claimDispatches(String claimant, Instant now, int limit,
                                                Duration claimDuration, Set<DispatchType> dispatchTypes);
    boolean markClaimedDispatchSentFor(String outboxId, String claimant, int claimAttempt, Duration ackTimeout);
    boolean acknowledgeDispatch(String executionId, Instant now);
    boolean retryClaimedDispatchAfter(String outboxId, String claimant, int claimAttempt,
                                      Duration delay, String error, int maxAttempts);
    boolean completeDispatch(String outboxId, Instant now);
    boolean cancelDispatch(String executionId, Instant now, String reason);
}
