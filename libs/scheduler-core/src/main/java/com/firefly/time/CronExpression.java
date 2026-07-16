package com.firefly.time;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Spring 5.3 compatible six-field cron expression backed by cron-utils. */
public final class CronExpression {
    private static final CronParser PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53)
    );

    private final String expression;
    private final ExecutionTime executionTime;

    private CronExpression(String expression, Cron cron) {
        this.expression = expression;
        this.executionTime = ExecutionTime.forCron(cron);
    }

    public static CronExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String normalized = expression.trim();
        if (normalized.split("\\s+").length != 6) {
            throw new IllegalArgumentException(
                    "cron expression must contain 6 fields: second minute hour day month week"
            );
        }
        Cron cron = PARSER.parse(normalized);
        cron.validate();
        return new CronExpression(normalized, cron);
    }

    public Instant nextAfter(Instant after, ZoneId zoneId) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(zoneId, "zoneId");
        ZonedDateTime zonedAfter = after.atZone(zoneId);
        LocalDateTime localAfter = zonedAfter.toLocalDateTime().withNano(0);
        if (executionTime.isMatch(zonedAfter.withNano(0))) {
            for (ZoneOffset offset : zoneId.getRules().getValidOffsets(localAfter)) {
                ZonedDateTime overlapCandidate = ZonedDateTime.ofLocal(localAfter, zoneId, offset);
                if (overlapCandidate.toInstant().isAfter(after)) return overlapCandidate.toInstant();
            }
        }
        return executionTime.nextExecution(zonedAfter)
                .orElseThrow(() -> new IllegalStateException("no next fire time for cron: " + expression))
                .toInstant();
    }

    @Override
    public String toString() {
        return expression;
    }
}
