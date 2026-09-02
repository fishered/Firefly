package com.firefly.executor;

import com.firefly.domain.ExecutorRoutingStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Filters executor instances by capacity before applying stable routing. */
public final class ResourceAwareExecutorSelector {
    public Optional<ExecutorResourceSnapshot> select(List<ExecutorResourceSnapshot> snapshots,
                                                      ExecutorResourceRequirement requirement,
                                                      ExecutorRoutingStrategy strategy,
                                                      String routingKey) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(routingKey, "routingKey");
        List<ExecutorResourceSnapshot> candidates = snapshots.stream()
                .filter(value -> value.availableCpuMillis() >= requirement.cpuMillis())
                .filter(value -> value.availableMemoryBytes() >= requirement.memoryBytes())
                .filter(value -> value.tags().containsAll(requirement.requiredTags()))
                .filter(value -> requirement.maxTenantConcurrent() == 0
                        || value.tenantActiveExecutions() < requirement.maxTenantConcurrent())
                .sorted(Comparator.comparing(ExecutorResourceSnapshot::instanceId))
                .toList();
        if (candidates.isEmpty()) return Optional.empty();
        if (strategy == ExecutorRoutingStrategy.CONSISTENT_HASH) {
            return candidates.stream().max((left, right) -> Long.compareUnsigned(
                    score(routingKey, left.instanceId()), score(routingKey, right.instanceId())));
        }
        return Optional.of(candidates.get(0));
    }

    private long score(String routingKey, String instanceId) {
        long hash = 0xcbf29ce484222325L;
        String value = routingKey + '\u0000' + instanceId;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
