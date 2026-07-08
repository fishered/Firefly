package io.github.nishi.firefly.domain;

import lombok.Builder;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines a logical executor that one or more service instances can register under.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record ExecutorDefinition(
        String name,
        String description,
        Set<ExecutorProtocol> protocols,
        Map<String, String> metadata,
        boolean enabled
) {
    public ExecutorDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        protocols = Set.copyOf(Objects.requireNonNull(protocols, "protocols"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (protocols.isEmpty()) {
            throw new IllegalArgumentException("protocols must not be empty");
        }
    }

    /**
     * Defaults to embedded execution so local and traditional services have a zero-network path.
     */
    public static Builder builder() {
        return newBuilder()
                .description("")
                .protocols(Set.of(ExecutorProtocol.EMBEDDED))
                .metadata(Map.of())
                .enabled(true);
    }
}
