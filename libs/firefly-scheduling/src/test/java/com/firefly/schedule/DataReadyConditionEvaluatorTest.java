package com.firefly.schedule;

import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataReadyConditionEvaluatorTest {
    private static final Instant BUSINESS_TIME = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void combinesConfiguredConditionsAndFailsClosedForUnknownIds() {
        DataReadyConditionEvaluator evaluator = DataReadyConditionEvaluator.of(
                condition("batch", ConditionStatus.ALLOWED),
                condition("manifest", ConditionStatus.WAITING)
        );
        JobDefinition job = job("batch,manifest");

        assertEquals(ConditionStatus.WAITING, evaluator.evaluate(job, BUSINESS_TIME));
        assertEquals(ConditionStatus.BLOCKED,
                evaluator.evaluate(job("batch,missing"), BUSINESS_TIME));
    }

    @Test
    void blockedTakesPrecedenceAndLegacyJobsRemainAllowed() {
        DataReadyConditionEvaluator evaluator = DataReadyConditionEvaluator.of(
                condition("database", ConditionStatus.BLOCKED),
                condition("storage", ConditionStatus.WAITING)
        );

        assertEquals(ConditionStatus.ALLOWED, evaluator.evaluate(job(""), BUSINESS_TIME));
        assertEquals(ConditionStatus.BLOCKED,
                evaluator.evaluate(job("storage,database"), BUSINESS_TIME));
    }

    @Test
    void rejectsDuplicateConditionIds() {
        assertThrows(IllegalArgumentException.class, () -> new DataReadyConditionEvaluator(java.util.List.of(
                condition("database", ConditionStatus.ALLOWED),
                condition("database", ConditionStatus.ALLOWED)
        )));
    }

    private static DataReadyCondition condition(String id, ConditionStatus status) {
        return new DataReadyCondition() {
            @Override public String id() { return id; }
            @Override public ConditionStatus evaluate(JobDefinition definition, Instant businessTime) { return status; }
        };
    }

    private static JobDefinition job(String conditions) {
        return JobDefinition.builder()
                .id("job")
                .name("job")
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .parameters(conditions.isBlank() ? Map.of() : Map.of(DataReadyConditionEvaluator.CONDITION_IDS_PARAMETER, conditions))
                .build();
    }
}
