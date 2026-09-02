package com.firefly.execution;

import java.util.Map;
import java.util.Objects;

/** Versioned inputs captured for an execution replay comparison. */
public record ReplayDefinitionSnapshot(long definitionRevision, long calendarRevision,
                                       long dependencyRevision, Map<String, String> parameters) {
    public ReplayDefinitionSnapshot {
        if (definitionRevision < 1 || calendarRevision < 0 || dependencyRevision < 0) {
            throw new IllegalArgumentException("invalid replay snapshot revisions");
        }
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }
}
