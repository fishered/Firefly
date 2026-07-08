package io.github.nishi.firefly.domain;

import lombok.Builder;

import java.util.Map;
import java.util.Objects;

/**
 * Groups jobs by the executor that should run them.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record JobGroupDefinition(
        String id,
        String name,
        String executorName,
        Map<String, String> metadata,
        boolean enabled
) {
    public JobGroupDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(executorName, "executorName");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must not be blank");
        }
    }

    /**
     * Provides the built-in group used by simple embedded deployments.
     */
    public static Builder builder() {
        return newBuilder()
                .metadata(Map.of())
                .enabled(true);
    }
}
