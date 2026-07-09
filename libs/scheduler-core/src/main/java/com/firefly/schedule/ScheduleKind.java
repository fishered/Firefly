package com.firefly.schedule;

/**
 * Persistable schedule families supported by Firefly configuration.
 */
public enum ScheduleKind {
    CRON,
    FIXED_RATE,
    DAILY_TIME,
    LINEAR_BACKOFF,
    MANUAL
}
