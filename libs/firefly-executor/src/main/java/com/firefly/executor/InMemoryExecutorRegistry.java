package com.firefly.executor;

import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorInstanceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps executor liveness in memory while preserving the same lease semantics expected from persistent stores.
 */
public final class InMemoryExecutorRegistry implements ExecutorRegistry {
    private final Object lock = new Object();
    private final Map<InstanceKey, ExecutorInstance> instances = new HashMap<>();

    @Override
    public void register(ExecutorInstance instance) {
        Objects.requireNonNull(instance, "instance");
        synchronized (lock) {
            instances.put(InstanceKey.from(instance), instance);
        }
    }

    @Override
    public Optional<ExecutorInstance> find(String executorName, String instanceId) {
        synchronized (lock) {
            return Optional.ofNullable(instances.get(new InstanceKey(executorName, instanceId)));
        }
    }

    @Override
    public boolean heartbeat(String executorName, String instanceId, Instant heartbeatAt) {
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        synchronized (lock) {
            InstanceKey key = new InstanceKey(executorName, instanceId);
            ExecutorInstance current = instances.get(key);
            if (current == null) {
                return false;
            }
            instances.put(key, copyWithHeartbeat(current, heartbeatAt, ExecutorInstanceStatus.ONLINE));
            return true;
        }
    }

    @Override
    public boolean markOffline(String executorName, String instanceId) {
        synchronized (lock) {
            InstanceKey key = new InstanceKey(executorName, instanceId);
            ExecutorInstance current = instances.get(key);
            if (current == null) {
                return false;
            }
            instances.put(key, copyWithHeartbeat(current, current.lastHeartbeatAt(), ExecutorInstanceStatus.OFFLINE));
            return true;
        }
    }

    @Override
    public List<ExecutorInstance> listAll() {
        synchronized (lock) {
            return instances.values().stream()
                    .sorted(Comparator.comparing(ExecutorInstance::executorName)
                            .thenComparing(ExecutorInstance::instanceId))
                    .toList();
        }
    }

    @Override
    public List<ExecutorInstance> listOnline(String executorName, Instant now, Duration heartbeatTimeout) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        Instant oldestAllowedHeartbeat = now.minus(heartbeatTimeout);
        synchronized (lock) {
            return instances.values().stream()
                    .filter(instance -> instance.executorName().equals(executorName))
                    .filter(instance -> instance.status() == ExecutorInstanceStatus.ONLINE)
                    .filter(instance -> !instance.lastHeartbeatAt().isBefore(oldestAllowedHeartbeat))
                    .sorted(Comparator.comparing(ExecutorInstance::instanceId))
                    .toList();
        }
    }

    private static ExecutorInstance copyWithHeartbeat(
            ExecutorInstance current,
            Instant lastHeartbeatAt,
            ExecutorInstanceStatus status
    ) {
        return new ExecutorInstance(
                current.executorName(),
                current.instanceId(),
                current.sessionId(),
                current.gatewayNodeId(),
                current.serviceName(),
                current.host(),
                current.port(),
                current.protocol(),
                current.registeredAt(),
                lastHeartbeatAt,
                status,
                current.metadata()
        );
    }

    private record InstanceKey(String executorName, String instanceId) {
        private InstanceKey {
            Objects.requireNonNull(executorName, "executorName");
            Objects.requireNonNull(instanceId, "instanceId");
        }

        private static InstanceKey from(ExecutorInstance instance) {
            return new InstanceKey(instance.executorName(), instance.instanceId());
        }
    }
}
