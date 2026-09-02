package com.firefly.schedule;

import com.firefly.domain.JobDefinition;

import java.time.Instant;

/**
 * Pluggable business-data predicate evaluated before an execution is created.
 * Implementations may adapt a database watermark, object-store manifest, Kafka
 * offset, or an external readiness endpoint without coupling the scheduler to
 * that system's client library.
 */
public interface DataReadyCondition {
    String id();

    ConditionStatus evaluate(JobDefinition definition, Instant businessTime);
}
