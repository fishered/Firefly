package com.firefly.domain;

import java.time.Instant;
import java.util.Map;

public record ExecutionContext(
        String executionId,
        String rootExecutionId,
        int runAttempt,
        String jobId,
        String handlerName,
        Instant scheduledFireTime,
        Instant dispatchTime,
        Instant actualFireTime,
        Map<String, String> parameters
) {
    public ExecutionContext(
            String executionId, String jobId, String handlerName, Instant scheduledFireTime,
            Instant dispatchTime, Instant actualFireTime, Map<String, String> parameters
    ) {
        this(executionId, executionId, 0, jobId, handlerName, scheduledFireTime,
                dispatchTime, actualFireTime, parameters);
    }
}

