package com.firefly.domain;

import java.time.Instant;
import java.time.ZoneId;

public interface Schedule {
    Instant nextAfter(Instant after, ZoneId zoneId);
}

