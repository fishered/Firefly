package io.github.nishi.firefly.domain;

import java.time.Instant;
import java.util.Map;

public record ExecutionContext(
        String executionId,
        String jobId,
        String handlerName,
        Instant scheduledFireTime,
        Instant dispatchTime,
        Instant actualFireTime,
        Map<String, String> parameters
) {
}

