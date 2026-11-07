package com.firefly.governance;

/** Resource dimensions supported by quota planning. */
public enum TenantCapacityResource {
    CONCURRENCY("concurrency"),
    CPU_MILLIS("cpuMillis"),
    MEMORY_BYTES("memoryBytes"),
    BACKLOG("backlog"),
    TRIGGER_RATE("triggerRatePerSecond");

    private final String jsonName;

    TenantCapacityResource(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
