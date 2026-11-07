package com.firefly.governance;

import java.util.Objects;

/** Resource units requested atomically at an admission boundary. */
public record TenantAdmissionRequest(
        String reservationId,
        String tenantId,
        int concurrentExecutions,
        long cpuMillis,
        long memoryBytes,
        long backlog,
        long triggers
) {
    public TenantAdmissionRequest {
        Objects.requireNonNull(reservationId, "reservationId");
        tenantId = tenantId == null || tenantId.isBlank() ? TenantDefinition.DEFAULT_TENANT_ID : tenantId;
        if (reservationId.isBlank()) throw new IllegalArgumentException("reservationId must not be blank");
        if (concurrentExecutions < 0 || cpuMillis < 0 || memoryBytes < 0 || backlog < 0 || triggers < 0) {
            throw new IllegalArgumentException("requested resource values must not be negative");
        }
        if (concurrentExecutions == 0 && cpuMillis == 0 && memoryBytes == 0 && backlog == 0 && triggers == 0) {
            throw new IllegalArgumentException("at least one resource unit must be requested");
        }
    }
}
