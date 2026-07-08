package io.github.nishi.firefly.domain;

import java.time.Instant;
import java.util.Map;

public record ExecutionContext(
        String jobId,
        String handlerName,
        Instant scheduledFireTime,
        Instant actualFireTime,
        Map<String, String> parameters
) {
}

