package com.firefly.schedule;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the condition ids declared by a job and combines their states.
 * Missing declarations keep legacy jobs allowed; a missing configured
 * condition fails closed so a typo cannot accidentally dispatch a job early.
 */
public final class DataReadyConditionEvaluator implements SchedulingConditionEvaluator {
    public static final String CONDITION_IDS_PARAMETER = "firefly.data-ready.conditions";

    private final Map<String, DataReadyCondition> conditions;

    public DataReadyConditionEvaluator(Collection<? extends DataReadyCondition> conditions) {
        Objects.requireNonNull(conditions, "conditions");
        Map<String, DataReadyCondition> indexed = new LinkedHashMap<>();
        for (DataReadyCondition condition : conditions) {
            Objects.requireNonNull(condition, "condition");
            if (condition.id() == null || condition.id().isBlank()) {
                throw new IllegalArgumentException("condition id must not be blank");
            }
            if (indexed.putIfAbsent(condition.id(), condition) != null) {
                throw new IllegalArgumentException("duplicate condition id: " + condition.id());
            }
        }
        this.conditions = Map.copyOf(indexed);
    }

    public static DataReadyConditionEvaluator of(DataReadyCondition... conditions) {
        return new DataReadyConditionEvaluator(List.of(conditions));
    }

    @Override
    public ConditionStatus evaluate(JobDefinition definition, Instant businessTime) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(businessTime, "businessTime");
        String declaration = definition.parameters().getOrDefault(CONDITION_IDS_PARAMETER, "");
        if (declaration.isBlank()) return ConditionStatus.ALLOWED;

        ConditionStatus result = ConditionStatus.ALLOWED;
        for (String rawId : declaration.split(",")) {
            String id = rawId.trim();
            if (id.isBlank()) continue;
            DataReadyCondition condition = conditions.get(id);
            ConditionStatus status = condition == null
                    ? ConditionStatus.BLOCKED
                    : Objects.requireNonNull(condition.evaluate(definition, businessTime),
                            "condition result for " + id);
            if (status == ConditionStatus.BLOCKED) return status;
            if (status == ConditionStatus.WAITING) result = status;
        }
        return result;
    }
}
