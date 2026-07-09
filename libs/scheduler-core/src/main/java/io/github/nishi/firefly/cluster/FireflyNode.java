package io.github.nishi.firefly.cluster;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Describes one Firefly process and its cluster responsibilities.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record FireflyNode(
        String nodeId,
        Set<NodeRole> roles,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        NodeStatus status,
        Map<String, String> metadata
) {
    public FireflyNode {
        Objects.requireNonNull(nodeId, "nodeId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        Objects.requireNonNull(status, "status");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
    }

    /**
     * Defaults to an online standalone node; cluster deployments should override roles explicitly.
     */
    public static Builder builder() {
        return newBuilder()
                .roles(Set.of(NodeRole.SCHEDULER, NodeRole.GATEWAY, NodeRole.API))
                .status(NodeStatus.ONLINE)
                .metadata(Map.of());
    }
}
