package com.firefly.store;

import com.firefly.engine.ExecutionCommand;

import java.time.Instant;
import java.util.Objects;

public record DispatchOutboxRecord(
        String outboxId,
        ExecutionCommand command,
        DispatchType dispatchType,
        DispatchOutboxStatus status,
        int attempt,
        Instant availableAt,
        String claimOwner,
        Instant claimUntil,
        Instant ackDeadline,
        String lastError
) {
    public DispatchOutboxRecord {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(dispatchType, "dispatchType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        claimOwner = claimOwner == null ? "" : claimOwner;
        lastError = lastError == null ? "" : lastError;
    }
}
