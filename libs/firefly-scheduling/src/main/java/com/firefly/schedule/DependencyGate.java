package com.firefly.schedule;

import java.time.Instant;
import java.util.Objects;

/** Durable pending trigger while a job waits for prerequisites. */
public record DependencyGate(String gateId, String jobId, Instant businessTime,
                             Instant nextCheckAt, Instant deadlineAt, int waitAttempts,
                             DependencyGateStatus status, String reason) {
    public DependencyGate {
        if (gateId == null || gateId.isBlank() || jobId == null || jobId.isBlank()) throw new IllegalArgumentException("gate identity is required");
        Objects.requireNonNull(businessTime); Objects.requireNonNull(nextCheckAt); Objects.requireNonNull(deadlineAt); Objects.requireNonNull(status);
        if (deadlineAt.isBefore(businessTime) || waitAttempts < 0) throw new IllegalArgumentException("invalid dependency gate");
        reason = reason == null ? "" : reason;
    }
    public DependencyGate next(Instant checkAt, int attempts) {
        return new DependencyGate(gateId, jobId, businessTime, checkAt, deadlineAt, attempts, status, reason);
    }
    public DependencyGate withStatus(DependencyGateStatus value, String detail) {
        return new DependencyGate(gateId, jobId, businessTime, nextCheckAt, deadlineAt, waitAttempts, value, detail);
    }
}
