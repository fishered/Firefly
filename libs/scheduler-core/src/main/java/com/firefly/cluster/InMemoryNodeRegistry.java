package com.firefly.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory node registry used by local HA tests and non-persistent deployments.
 */
public final class InMemoryNodeRegistry implements NodeRegistry {
    private final Object lock = new Object();
    private final Map<String, FireflyNode> nodes = new HashMap<>();

    @Override
    public void register(FireflyNode node) {
        Objects.requireNonNull(node, "node");
        synchronized (lock) {
            nodes.put(node.nodeId(), node);
        }
    }

    @Override
    public Optional<FireflyNode> find(String nodeId) {
        synchronized (lock) {
            return Optional.ofNullable(nodes.get(nodeId));
        }
    }

    @Override
    public boolean heartbeat(String nodeId, Instant heartbeatAt) {
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        synchronized (lock) {
            FireflyNode current = nodes.get(nodeId);
            if (current == null) {
                return false;
            }
            nodes.put(nodeId, copyWith(current, heartbeatAt, current.status()));
            return true;
        }
    }

    @Override
    public boolean markOffline(String nodeId) {
        synchronized (lock) {
            FireflyNode current = nodes.get(nodeId);
            if (current == null) {
                return false;
            }
            nodes.put(nodeId, copyWith(current, current.lastHeartbeatAt(), NodeStatus.OFFLINE));
            return true;
        }
    }

    @Override
    public boolean markDraining(String nodeId) {
        synchronized (lock) {
            FireflyNode current = nodes.get(nodeId);
            if (current == null) return false;
            nodes.put(nodeId, copyWith(current, current.lastHeartbeatAt(), NodeStatus.DRAINING));
            return true;
        }
    }

    @Override
    public List<FireflyNode> listOnline(Instant now, Duration heartbeatTimeout) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        Instant oldestAllowedHeartbeat = now.minus(heartbeatTimeout);
        synchronized (lock) {
            return nodes.values().stream()
                    .filter(node -> node.status() == NodeStatus.ONLINE)
                    .filter(node -> !node.lastHeartbeatAt().isBefore(oldestAllowedHeartbeat))
                    .sorted(Comparator.comparing(FireflyNode::nodeId))
                    .toList();
        }
    }

    @Override
    public List<FireflyNode> listAll() {
        synchronized (lock) {
            return nodes.values().stream().sorted(Comparator.comparing(FireflyNode::nodeId)).toList();
        }
    }

    private FireflyNode copyWith(FireflyNode current, Instant heartbeatAt, NodeStatus status) {
        return new FireflyNode(
                current.nodeId(),
                current.roles(),
                current.registeredAt(),
                heartbeatAt,
                status,
                current.metadata()
        );
    }
}
