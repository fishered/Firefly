package com.firefly.governance;

/** Stable reason codes exposed through Admin APIs, metrics and audit events. */
public enum TenantAdmissionReason {
    ADMITTED,
    IDEMPOTENT_REPLAY,
    TENANT_NOT_FOUND,
    TENANT_DISABLED,
    CONCURRENCY_QUOTA_EXCEEDED,
    CPU_QUOTA_EXCEEDED,
    MEMORY_QUOTA_EXCEEDED,
    BACKLOG_QUOTA_EXCEEDED,
    TRIGGER_RATE_QUOTA_EXCEEDED,
    OUTBOX_REJECTED
}
