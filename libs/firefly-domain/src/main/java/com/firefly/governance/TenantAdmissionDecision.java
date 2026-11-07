package com.firefly.governance;

import java.util.Objects;
import java.util.Optional;

/** Explainable result of a quota-aware admission attempt. */
public record TenantAdmissionDecision(
        boolean admitted,
        TenantAdmissionReason reason,
        TenantUsage usage,
        Optional<TenantReservation> reservation
) {
    public TenantAdmissionDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(usage, "usage");
        reservation = reservation == null ? Optional.empty() : reservation;
        if (admitted != reservation.isPresent()) {
            throw new IllegalArgumentException("admitted decisions must carry a reservation");
        }
    }

    public static TenantAdmissionDecision admitted(TenantAdmissionReason reason,
                                                    TenantUsage usage, TenantReservation reservation) {
        return new TenantAdmissionDecision(true, reason, usage, Optional.of(reservation));
    }

    public static TenantAdmissionDecision rejected(TenantAdmissionReason reason, TenantUsage usage) {
        return new TenantAdmissionDecision(false, reason, usage, Optional.empty());
    }
}
