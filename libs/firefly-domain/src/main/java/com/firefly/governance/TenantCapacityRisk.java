package com.firefly.governance;

/** Stable risk ordering used by previews and tenant reports. */
public enum TenantCapacityRisk {
    LOW(0),
    UNKNOWN(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int severity;

    TenantCapacityRisk(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    public static TenantCapacityRisk max(TenantCapacityRisk left, TenantCapacityRisk right) {
        return left.severity >= right.severity ? left : right;
    }
}
