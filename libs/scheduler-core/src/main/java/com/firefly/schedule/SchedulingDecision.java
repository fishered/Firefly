package com.firefly.schedule;

import java.time.Instant;

public record SchedulingDecision(Decision decision, Instant fireTime, Instant effectiveTime, String reason) {
    public enum Decision { FIRE, SKIP, DELAY }

    public SchedulingDecision {
        if (decision == null || fireTime == null || effectiveTime == null || reason == null) {
            throw new NullPointerException("decision, times and reason are required");
        }
    }

    public boolean shouldDispatch() { return decision == Decision.FIRE || decision == Decision.DELAY; }
}
