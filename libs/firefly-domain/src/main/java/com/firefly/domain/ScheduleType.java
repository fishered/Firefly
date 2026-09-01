package com.firefly.domain;

/**
 * Stable schedule types supported by the Firefly engine and persistence stores.
 */
public enum ScheduleType {
    CRON(true),
    FIXED_RATE(true),
    UNSUPPORTED(false);

    private final boolean executable;

    ScheduleType(boolean executable) {
        this.executable = executable;
    }

    public boolean executable() {
        return executable;
    }
}
