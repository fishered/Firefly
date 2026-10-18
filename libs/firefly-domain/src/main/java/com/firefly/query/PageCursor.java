package com.firefly.query;

import java.time.Instant;
import java.util.Objects;

/** Stable descending keyset cursor composed of the sort timestamp and unique identifier. */
public record PageCursor(Instant timestamp, String id) {
    public PageCursor {
        Objects.requireNonNull(timestamp, "timestamp");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("cursor id must not be blank");
    }
}
