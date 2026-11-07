package com.firefly.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Shared consistency boundary for dynamic tenant policy and cluster-wide resource admission. */
public interface TenantQuotaStore {
    TenantDefinition save(TenantDefinition definition, long expectedVersion, Instant now);

    Optional<TenantDefinition> find(String tenantId);

    List<TenantDefinition> list();

    TenantUsage usage(String tenantId, Instant now);

    TenantAdmissionDecision tryReserve(TenantAdmissionRequest request, Instant now, Duration leaseDuration);

    boolean release(String reservationId, Instant now);

    int reclaimExpired(Instant now, int limit);

    /** Repairs backlog reservations left between quota admission and outbox commit/terminal cleanup. */
    default int reconcileBacklogReservations(Instant now, Duration orphanGrace, int limit) {
        return 0;
    }
}
