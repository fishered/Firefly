package com.firefly.governance;

/** Stable quality flags attached to capacity evidence and recommendations. */
public enum TenantCapacityQuality {
    COMPLETE,
    INSUFFICIENT_DATA,
    CENSORED_BY_QUOTA,
    MIXED_POLICY,
    MIXED_VERSION
}
