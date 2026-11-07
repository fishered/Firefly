package com.firefly.store;

import com.firefly.governance.TenantAdmissionReason;

import java.util.Objects;

/** Explicit outcome for ingress paths that need to expose quota-aware backpressure. */
public record EnqueueAdmissionResult(
        boolean accepted,
        boolean duplicate,
        TenantAdmissionReason reason
) {
    public EnqueueAdmissionResult {
        Objects.requireNonNull(reason, "reason");
        if (accepted && duplicate) throw new IllegalArgumentException("accepted enqueue cannot be duplicate");
    }

    public static EnqueueAdmissionResult acceptedResult() {
        return new EnqueueAdmissionResult(true, false, TenantAdmissionReason.ADMITTED);
    }

    public static EnqueueAdmissionResult duplicateResult() {
        return new EnqueueAdmissionResult(false, true, TenantAdmissionReason.IDEMPOTENT_REPLAY);
    }

    public static EnqueueAdmissionResult rejected(TenantAdmissionReason reason) {
        return new EnqueueAdmissionResult(false, false, reason);
    }
}
