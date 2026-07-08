package io.github.nishi.firefly.domain;

import io.github.nishi.firefly.time.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class CronSchedule implements Schedule {
    private final String expression;
    private final CronExpression cronExpression;

    public CronSchedule(String expression) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.cronExpression = CronExpression.parse(expression);
    }

    public String expression() {
        return expression;
    }

    @Override
    public Instant nextAfter(Instant after, ZoneId zoneId) {
        return cronExpression.nextAfter(after, zoneId);
    }

    @Override
    public String toString() {
        return expression;
    }
}

