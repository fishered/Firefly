package com.firefly.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class FixedRateSchedule implements Schedule {
    private final Duration interval;

    public FixedRateSchedule(Duration interval) {
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    public Duration interval() {
        return interval;
    }

    @Override
    public ScheduleType type() {
        return ScheduleType.FIXED_RATE;
    }

    @Override
    public Instant nextAfter(Instant after, ZoneId zoneId) {
        return after.plus(interval);
    }

    @Override
    public String toString() {
        return "fixed-rate:" + interval;
    }
}

