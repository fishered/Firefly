package com.firefly.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public final class CronExpression {
    private static final int MAX_SEARCH_SECONDS = 366 * 24 * 60 * 60;

    private final String expression;
    private final CronField seconds;
    private final CronField minutes;
    private final CronField hours;
    private final CronField daysOfMonth;
    private final CronField months;
    private final CronField daysOfWeek;

    private CronExpression(
            String expression,
            CronField seconds,
            CronField minutes,
            CronField hours,
            CronField daysOfMonth,
            CronField months,
            CronField daysOfWeek
    ) {
        this.expression = expression;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
    }

    public static CronExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("cron expression must contain 6 fields: second minute hour day month week");
        }
        return new CronExpression(
                expression,
                CronField.parse(parts[0], 0, 59),
                CronField.parse(parts[1], 0, 59),
                CronField.parse(parts[2], 0, 23),
                CronField.parse(parts[3], 1, 31),
                CronField.parse(parts[4], 1, 12),
                CronField.parse(parts[5], 0, 7)
        );
    }

    public Instant nextAfter(Instant after, ZoneId zoneId) {
        ZonedDateTime cursor = after.atZone(zoneId).plusSeconds(1).withNano(0);
        for (int i = 0; i < MAX_SEARCH_SECONDS; i++) {
            if (matches(cursor.toLocalDateTime())) {
                return cursor.toInstant();
            }
            cursor = cursor.plusSeconds(1);
        }
        throw new IllegalStateException("no next fire time found within one year for cron: " + expression);
    }

    private boolean matches(LocalDateTime dateTime) {
        int cronWeek = dateTime.getDayOfWeek().getValue() % 7;
        return seconds.matches(dateTime.getSecond())
                && minutes.matches(dateTime.getMinute())
                && hours.matches(dateTime.getHour())
                && months.matches(dateTime.getMonthValue())
                && daysOfMonth.matches(dateTime.getDayOfMonth())
                && (daysOfWeek.matches(cronWeek) || daysOfWeek.matches(7));
    }

    @Override
    public String toString() {
        return expression;
    }

    private static final class CronField {
        private final BitSet allowed;
        private final int min;
        private final int max;

        private CronField(BitSet allowed, int min, int max) {
            this.allowed = allowed;
            this.min = min;
            this.max = max;
        }

        static CronField parse(String value, int min, int max) {
            BitSet allowed = new BitSet(max + 1);
            if ("*".equals(value) || "?".equals(value)) {
                allowed.set(min, max + 1);
                return new CronField(allowed, min, max);
            }

            List<String> parts = List.of(value.split(","));
            for (String part : parts) {
                applyPart(allowed, part.trim(), min, max);
            }
            return new CronField(allowed, min, max);
        }

        private static void applyPart(BitSet allowed, String part, int min, int max) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("empty cron field segment");
            }

            String rangePart = part;
            int step = 1;
            if (part.contains("/")) {
                String[] stepParts = part.split("/", -1);
                if (stepParts.length != 2) {
                    throw new IllegalArgumentException("invalid cron step: " + part);
                }
                rangePart = stepParts[0];
                step = Integer.parseInt(stepParts[1]);
                if (step < 1) {
                    throw new IllegalArgumentException("cron step must be positive: " + part);
                }
            }

            int start;
            int end;
            if ("*".equals(rangePart) || "?".equals(rangePart)) {
                start = min;
                end = max;
            } else if (rangePart.contains("-")) {
                String[] range = rangePart.split("-", -1);
                if (range.length != 2) {
                    throw new IllegalArgumentException("invalid cron range: " + part);
                }
                start = Integer.parseInt(range[0]);
                end = Integer.parseInt(range[1]);
            } else {
                start = Integer.parseInt(rangePart);
                end = start;
            }

            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException("cron value out of range: " + part);
            }
            for (int i = start; i <= end; i += step) {
                allowed.set(i);
            }
        }

        boolean matches(int value) {
            if (value < min || value > max) {
                return false;
            }
            return allowed.get(value);
        }
    }
}

