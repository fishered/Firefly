package com.firefly.schedule;

import com.firefly.domain.JobDefinition;
import java.time.Instant;

/** Extension point for business predicates evaluated before execution creation. */
@FunctionalInterface
public interface SchedulingConditionEvaluator {
    ConditionStatus evaluate(JobDefinition definition, Instant businessTime);

    static SchedulingConditionEvaluator allowAll() { return (definition, time) -> ConditionStatus.ALLOWED; }
}
