package io.github.nishi.firefly.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tracks Firefly cluster nodes and heartbeat freshness.
 */
public interface NodeRegistry {
    void register(FireflyNode node);

    Optional<FireflyNode> find(String nodeId);

    boolean heartbeat(String nodeId, Instant heartbeatAt);

    boolean markOffline(String nodeId);

    List<FireflyNode> listOnline(Instant now, Duration heartbeatTimeout);
}
