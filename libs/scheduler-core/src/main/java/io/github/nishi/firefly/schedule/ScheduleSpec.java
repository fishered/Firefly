package io.github.nishi.firefly.schedule;

import lombok.Builder;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;

/**
 * Persistable schedule description that can be parsed into runtime schedule behavior.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record ScheduleSpec(
        ScheduleKind kind,
        String expression,
        Map<String, String> options
) {
    public ScheduleSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expression, "expression");
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
    }

    public static ScheduleSpec cron(String expression) {
        return builder()
                .kind(ScheduleKind.CRON)
                .expression(expression)
                .build();
    }

    public static ScheduleSpec fixedRate(Duration duration) {
        return builder()
                .kind(ScheduleKind.FIXED_RATE)
                .expression(duration.toString())
                .build();
    }

    public static ScheduleSpec dailyAt(LocalTime time) {
        return builder()
                .kind(ScheduleKind.DAILY_TIME)
                .expression(time.toString())
                .build();
    }

    public static ScheduleSpec linearBackoff(Duration firstDelay, Duration step, Duration maxDelay) {
        return builder()
                .kind(ScheduleKind.LINEAR_BACKOFF)
                .expression(firstDelay.toString())
                .options(Map.of("step", step.toString(), "maxDelay", maxDelay.toString()))
                .build();
    }

    public static ScheduleSpec manual() {
        return builder()
                .kind(ScheduleKind.MANUAL)
                .expression("")
                .build();
    }

    /**
     * Keeps optional parser settings immutable and absent by default.
     */
    public static Builder builder() {
        return newBuilder().options(Map.of());
    }
}
