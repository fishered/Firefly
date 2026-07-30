package com.firefly.domain;

import java.time.Instant;
import java.time.ZoneId;

public interface Schedule {
    Instant nextAfter(Instant after, ZoneId zoneId);

    /**
     * Unknown extension types remain source-compatible but must be explicitly supported before execution.
     */
    default ScheduleType type() {
        return ScheduleType.UNSUPPORTED;
    }
}

