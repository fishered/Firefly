package com.firefly.executor;

import com.firefly.domain.ExecutorRoutingStrategy;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Filters executor instances by capacity before applying stable routing. */
public final class ResourceAwareExecutorSelector {
    private final ConcurrentHashMap<String, AtomicInteger> routingCursors = new ConcurrentHashMap<>();

    /**
     * Compatibility overload for callers whose snapshot contains exactly one
     * executor. Mixed-executor snapshots are rejected instead of being routed
     * across unrelated handlers.
     */
    public Optional<ExecutorResourceSnapshot> select(List<ExecutorResourceSnapshot> snapshots,
                                                      ExecutorResourceRequirement requirement,
                                                      ExecutorRoutingStrategy strategy,
                                                      String routingKey) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) return Optional.empty();
        Set<String> executorNames = new HashSet<>();
        snapshots.forEach(snapshot -> executorNames.add(snapshot.executorName()));
        if (executorNames.size() != 1) {
            throw new IllegalArgumentException("executorName is required for mixed-executor snapshots");
        }
        return select(executorNames.iterator().next(), snapshots, requirement, strategy, routingKey);
    }

    public Optional<ExecutorResourceSnapshot> select(String executorName,
                                                      List<ExecutorResourceSnapshot> snapshots,
                                                      ExecutorResourceRequirement requirement,
                                                      ExecutorRoutingStrategy strategy,
                                                      String routingKey) {
        return selectAndReserve(executorName, snapshots, requirement, strategy, routingKey,
                (snapshot, ignored) -> true);
    }

    /**
     * Selects in routing order and atomically reserves the first candidate
     * accepted by the caller-owned admission store. A rejected stale snapshot
     * falls through to the next eligible candidate.
     */
    public Optional<ExecutorResourceSnapshot> selectAndReserve(
            String executorName,
            List<ExecutorResourceSnapshot> snapshots,
            ExecutorResourceRequirement requirement,
            ExecutorRoutingStrategy strategy,
            String routingKey,
            ExecutorResourceReservation reservation
    ) {
        if (executorName == null || executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must not be blank");
        }
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(reservation, "reservation");
        List<ExecutorResourceSnapshot> candidates = snapshots.stream()
                .filter(value -> value.executorName().equals(executorName))
                .filter(value -> value.availableCpuMillis() >= requirement.cpuMillis())
                .filter(value -> value.availableMemoryBytes() >= requirement.memoryBytes())
                .filter(value -> value.tags().containsAll(requirement.requiredTags()))
                .filter(value -> requirement.maxTenantConcurrent() == 0
                        || value.tenantActiveExecutions() < requirement.maxTenantConcurrent())
                .sorted(Comparator.comparing(ExecutorResourceSnapshot::instanceId))
                .toList();
        if (candidates.isEmpty()) return Optional.empty();
        for (ExecutorResourceSnapshot candidate : route(
                executorName, requirement.tenantId(), candidates, strategy, routingKey
        )) {
            if (reservation.tryReserve(candidate, requirement)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private List<ExecutorResourceSnapshot> route(
            String executorName,
            String tenantId,
            List<ExecutorResourceSnapshot> candidates,
            ExecutorRoutingStrategy strategy,
            String routingKey
    ) {
        if (strategy == ExecutorRoutingStrategy.CONSISTENT_HASH) {
            return candidates.stream().sorted((left, right) -> Long.compareUnsigned(
                    score(routingKey, right.instanceId()), score(routingKey, left.instanceId())
            )).toList();
        }
        String cursorKey = executorName + '\u0000' + tenantId + '\u0000' + routingKey;
        int start = strategy == ExecutorRoutingStrategy.RANDOM
                ? ThreadLocalRandom.current().nextInt(candidates.size())
                : Math.floorMod(routingCursors.computeIfAbsent(cursorKey, ignored -> new AtomicInteger())
                        .getAndIncrement(), candidates.size());
        return IntStream.range(0, candidates.size())
                .mapToObj(offset -> candidates.get((start + offset) % candidates.size()))
                .toList();
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
