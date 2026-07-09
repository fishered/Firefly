package com.firefly.schedule;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.Schedule;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Converts persisted schedule descriptions into runtime schedules understood by the engine.
 */
public final class ScheduleSpecParser {
    public Schedule parse(ScheduleSpec spec) {
        return switch (spec.kind()) {
            case CRON -> new CronSchedule(spec.expression());
            case FIXED_RATE -> new FixedRateSchedule(Duration.parse(spec.expression()));
            case DAILY_TIME -> parseDailyTime(spec.expression());
            case LINEAR_BACKOFF -> throw new UnsupportedOperationException(
                    "linear backoff requires persisted schedule state before it can be executed"
            );
            case MANUAL -> throw new UnsupportedOperationException(
                    "manual schedules do not produce automatic next-fire times"
            );
        };
    }

    private Schedule parseDailyTime(String expression) {
        LocalTime time = LocalTime.parse(expression);
        return new CronSchedule("0 %d %d * * *".formatted(time.getMinute(), time.getHour()));
    }
}
