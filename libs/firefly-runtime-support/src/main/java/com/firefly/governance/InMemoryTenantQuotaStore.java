package com.firefly.governance;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Process-local implementation with the same atomic and idempotent semantics as the JDBC store. */
public final class InMemoryTenantQuotaStore implements TenantQuotaStore {
    private final Map<String, TenantDefinition> definitions = new HashMap<>();
    private final Map<String, TenantUsage> usages = new HashMap<>();
    private final Map<String, TenantReservation> reservations = new HashMap<>();

    public InMemoryTenantQuotaStore() {
        TenantDefinition defaultTenant = TenantDefinition.defaultTenant(Instant.EPOCH);
        definitions.put(defaultTenant.tenantId(), defaultTenant);
        usages.put(defaultTenant.tenantId(), TenantUsage.empty(defaultTenant.tenantId(), Instant.EPOCH));
    }

    @Override
    public synchronized TenantDefinition save(TenantDefinition definition, long expectedVersion, Instant now) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        TenantDefinition current = definitions.get(definition.tenantId());
        long actualVersion = current == null ? 0 : current.version();
        if (actualVersion != expectedVersion) {
            throw new IllegalStateException("tenant version conflict: expected " + expectedVersion
                    + " but was " + actualVersion);
        }
        TenantDefinition saved = definition.withVersion(Math.addExact(actualVersion, 1), now);
        definitions.put(saved.tenantId(), saved);
        usages.putIfAbsent(saved.tenantId(), TenantUsage.empty(saved.tenantId(), now));
        return saved;
    }

    @Override
    public synchronized Optional<TenantDefinition> find(String tenantId) {
        return Optional.ofNullable(definitions.get(normalizeTenant(tenantId)));
    }

    @Override
    public synchronized List<TenantDefinition> list() {
        return definitions.values().stream().sorted(Comparator.comparing(TenantDefinition::tenantId)).toList();
    }

    @Override
    public synchronized TenantUsage usage(String tenantId, Instant now) {
        String normalized = normalizeTenant(tenantId);
        return rollWindow(usages.getOrDefault(normalized, TenantUsage.empty(normalized, now)), now);
    }

    @Override
    public synchronized TenantAdmissionDecision tryReserve(
            TenantAdmissionRequest request, Instant now, Duration leaseDuration
    ) {
        validateLease(leaseDuration);
        reclaimExpired(now, Integer.MAX_VALUE);
        TenantReservation previous = reservations.get(request.reservationId());
        if (previous != null) {
            validateReplay(previous, request);
            Instant renewedUntil = now.plus(leaseDuration);
            previous = new TenantReservation(previous.reservationId(), previous.tenantId(),
                    previous.concurrentExecutions(), previous.cpuMillis(), previous.memoryBytes(),
                    previous.backlog(), previous.triggers(), now,
                    renewedUntil.isAfter(previous.expiresAt()) ? renewedUntil : previous.expiresAt());
            reservations.put(previous.reservationId(), previous);
            return TenantAdmissionDecision.admitted(TenantAdmissionReason.IDEMPOTENT_REPLAY,
                    usage(previous.tenantId(), now), previous);
        }
        TenantDefinition definition = definitions.get(request.tenantId());
        TenantUsage current = rollWindow(
                usages.getOrDefault(request.tenantId(), TenantUsage.empty(request.tenantId(), now)), now
        );
        if (definition == null) {
            return TenantAdmissionDecision.rejected(TenantAdmissionReason.TENANT_NOT_FOUND, current);
        }
        TenantAdmissionReason rejection = rejection(definition, current, request);
        if (rejection != null) return TenantAdmissionDecision.rejected(rejection, current);

        TenantUsage updated = new TenantUsage(request.tenantId(),
                Math.addExact(current.activeExecutions(), request.concurrentExecutions()),
                Math.addExact(current.reservedCpuMillis(), request.cpuMillis()),
                Math.addExact(current.reservedMemoryBytes(), request.memoryBytes()),
                Math.addExact(current.backlog(), request.backlog()), current.triggerWindowStartedAt(),
                Math.addExact(current.triggersInWindow(), request.triggers()),
                Math.addExact(current.version(), 1));
        TenantReservation reservation = new TenantReservation(request.reservationId(), request.tenantId(),
                request.concurrentExecutions(), request.cpuMillis(), request.memoryBytes(), request.backlog(),
                request.triggers(), now, now.plus(leaseDuration));
        usages.put(request.tenantId(), updated);
        reservations.put(reservation.reservationId(), reservation);
        return TenantAdmissionDecision.admitted(TenantAdmissionReason.ADMITTED, updated, reservation);
    }

    @Override
    public synchronized boolean release(String reservationId, Instant now) {
        TenantReservation reservation = reservations.remove(reservationId);
        if (reservation == null) return false;
        releaseUsage(reservation, now);
        return true;
    }

    @Override
    public synchronized int reclaimExpired(Instant now, int limit) {
        if (limit < 1) return 0;
        List<TenantReservation> expired = reservations.values().stream()
                .filter(value -> !value.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(TenantReservation::expiresAt)
                        .thenComparing(TenantReservation::reservationId))
                .limit(limit).toList();
        for (TenantReservation reservation : new ArrayList<>(expired)) {
            if (reservations.remove(reservation.reservationId(), reservation)) releaseUsage(reservation, now);
        }
        return expired.size();
    }

    private void releaseUsage(TenantReservation reservation, Instant now) {
        TenantUsage current = rollWindow(usages.getOrDefault(reservation.tenantId(),
                TenantUsage.empty(reservation.tenantId(), now)), now);
        usages.put(reservation.tenantId(), new TenantUsage(reservation.tenantId(),
                subtract(current.activeExecutions(), reservation.concurrentExecutions()),
                subtract(current.reservedCpuMillis(), reservation.cpuMillis()),
                subtract(current.reservedMemoryBytes(), reservation.memoryBytes()),
                subtract(current.backlog(), reservation.backlog()), current.triggerWindowStartedAt(),
                current.triggersInWindow(), Math.addExact(current.version(), 1)));
    }

    private static TenantAdmissionReason rejection(TenantDefinition definition, TenantUsage usage,
                                                   TenantAdmissionRequest request) {
        if (!definition.enabled()) return TenantAdmissionReason.TENANT_DISABLED;
        if (exceeds(definition.maxConcurrent(), usage.activeExecutions(), request.concurrentExecutions()))
            return TenantAdmissionReason.CONCURRENCY_QUOTA_EXCEEDED;
        if (exceeds(definition.maxCpuMillis(), usage.reservedCpuMillis(), request.cpuMillis()))
            return TenantAdmissionReason.CPU_QUOTA_EXCEEDED;
        if (exceeds(definition.maxMemoryBytes(), usage.reservedMemoryBytes(), request.memoryBytes()))
            return TenantAdmissionReason.MEMORY_QUOTA_EXCEEDED;
        if (exceeds(definition.maxBacklog(), usage.backlog(), request.backlog()))
            return TenantAdmissionReason.BACKLOG_QUOTA_EXCEEDED;
        if (exceeds(definition.maxTriggerRatePerSecond(), usage.triggersInWindow(), request.triggers()))
            return TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED;
        return null;
    }

    private static TenantUsage rollWindow(TenantUsage usage, Instant now) {
        Instant window = now.truncatedTo(ChronoUnit.SECONDS);
        // A slow node must never roll the shared rate window backwards and regain tokens.
        if (!window.isAfter(usage.triggerWindowStartedAt())) return usage;
        return new TenantUsage(usage.tenantId(), usage.activeExecutions(), usage.reservedCpuMillis(),
                usage.reservedMemoryBytes(), usage.backlog(), window, 0, usage.version());
    }

    private static boolean exceeds(long limit, long current, long requested) {
        return limit > 0 && (requested > limit || current > limit - requested);
    }

    private static int subtract(int current, int released) {
        return Math.max(0, current - released);
    }

    private static long subtract(long current, long released) {
        return Math.max(0, current - released);
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? TenantDefinition.DEFAULT_TENANT_ID : tenantId;
    }

    private static void validateLease(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void validateReplay(TenantReservation reservation, TenantAdmissionRequest request) {
        if (!reservation.tenantId().equals(request.tenantId())
                || reservation.concurrentExecutions() != request.concurrentExecutions()
                || reservation.cpuMillis() != request.cpuMillis()
                || reservation.memoryBytes() != request.memoryBytes()
                || reservation.backlog() != request.backlog()
                || reservation.triggers() != request.triggers()) {
            throw new IllegalArgumentException("reservationId is already used for different resource units");
        }
    }
}
