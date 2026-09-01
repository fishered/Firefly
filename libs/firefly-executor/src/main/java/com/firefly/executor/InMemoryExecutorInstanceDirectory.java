package com.firefly.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryExecutorInstanceDirectory implements ExecutorInstanceDirectory {
    private final Map<Key, ExecutorInstanceLocation> locations = new ConcurrentHashMap<>();

    @Override
    public void register(ExecutorInstanceLocation location) {
        locations.put(new Key(location.executorName(), location.instanceId()), location);
    }

    @Override
    public boolean heartbeat(
            String executorName, String instanceId, String gatewayNodeId,
            String sessionId, Instant now, Duration leaseDuration
    ) {
        Key key = new Key(executorName, instanceId);
        return locations.computeIfPresent(key, (ignored, current) -> {
            if (!current.gatewayNodeId().equals(gatewayNodeId) || !current.sessionId().equals(sessionId)) {
                return current;
            }
            return new ExecutorInstanceLocation(
                    executorName, instanceId, gatewayNodeId, current.gatewayAddress(), sessionId,
                    now, now.plus(leaseDuration), current.metadata()
            );
        }) != null;
    }

    @Override
    public boolean markOffline(String executorName, String instanceId, String gatewayNodeId, String sessionId) {
        Key key = new Key(executorName, instanceId);
        ExecutorInstanceLocation current = locations.get(key);
        return current != null && current.gatewayNodeId().equals(gatewayNodeId)
                && current.sessionId().equals(sessionId) && locations.remove(key, current);
    }

    @Override
    public List<ExecutorInstanceLocation> listOnline(String executorName, Instant now) {
        return locations.values().stream()
                .filter(location -> location.executorName().equals(executorName))
                .filter(location -> location.leaseUntil().isAfter(now))
                .sorted(Comparator.comparing(ExecutorInstanceLocation::instanceId))
                .toList();
    }

    @Override
    public java.util.Optional<ExecutorInstanceLocation> findOnlineInstance(String instanceId, Instant now) {
        return locations.values().stream()
                .filter(location -> location.instanceId().equals(instanceId))
                .filter(location -> location.leaseUntil().isAfter(now))
                .findFirst();
    }

    private record Key(String executorName, String instanceId) {
    }
}
