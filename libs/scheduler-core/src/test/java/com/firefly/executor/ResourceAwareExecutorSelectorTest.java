package com.firefly.executor;

import com.firefly.domain.ExecutorRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceAwareExecutorSelectorTest {
    @Test
    void filtersCapacityTagsAndTenantBudgetBeforeRouting() {
        List<ExecutorResourceSnapshot> snapshots = List.of(
                new ExecutorResourceSnapshot("orders", "small", 500, 1024, Set.of("cpu"), 1, 1),
                new ExecutorResourceSnapshot("orders", "large", 2000, 4096, Set.of("cpu", "ssd"), 1, 0));
        ExecutorResourceRequirement requirement = new ExecutorResourceRequirement("billing", 1000, 2048, Set.of("ssd"), 5, 1);
        assertEquals("large", new ResourceAwareExecutorSelector().select(snapshots, requirement,
                ExecutorRoutingStrategy.ROUND_ROBIN, "billing-1").orElseThrow().instanceId());
    }

    @Test
    void returnsEmptyWhenNoCapacityIsAdmissible() {
        ExecutorResourceRequirement requirement = new ExecutorResourceRequirement("billing", 1000, 2048, Set.of(), 0, 1);
        assertTrue(new ResourceAwareExecutorSelector().select(List.of(
                new ExecutorResourceSnapshot("orders", "small", 500, 1024, Set.of(), 0, 1)),
                requirement, ExecutorRoutingStrategy.ROUND_ROBIN, "key").isEmpty());
    }

    @Test
    void filtersByExecutorNameAndRejectsAmbiguousCompatibilityCalls() {
        List<ExecutorResourceSnapshot> snapshots = List.of(
                new ExecutorResourceSnapshot("orders", "orders-1", 1000, 2048, Set.of(), 0, 0),
                new ExecutorResourceSnapshot("billing", "billing-1", 1000, 2048, Set.of(), 0, 0));
        ExecutorResourceRequirement requirement = ExecutorResourceRequirement.defaults("tenant-1");
        ResourceAwareExecutorSelector selector = new ResourceAwareExecutorSelector();

        assertEquals("orders-1", selector.select("orders", snapshots, requirement,
                ExecutorRoutingStrategy.ROUND_ROBIN, "key").orElseThrow().instanceId());
        assertThrows(IllegalArgumentException.class, () -> selector.select(
                snapshots, requirement, ExecutorRoutingStrategy.ROUND_ROBIN, "key"));
    }

    @Test
    void appliesRoundRobinInsteadOfAlwaysChoosingTheFirstInstance() {
        List<ExecutorResourceSnapshot> snapshots = List.of(
                new ExecutorResourceSnapshot("orders", "orders-1", 1000, 2048, Set.of(), 0, 0),
                new ExecutorResourceSnapshot("orders", "orders-2", 1000, 2048, Set.of(), 0, 0));
        ExecutorResourceRequirement requirement = ExecutorResourceRequirement.defaults("tenant-1");
        ResourceAwareExecutorSelector selector = new ResourceAwareExecutorSelector();

        assertEquals("orders-1", selector.select("orders", snapshots, requirement,
                ExecutorRoutingStrategy.ROUND_ROBIN, "key").orElseThrow().instanceId());
        assertEquals("orders-2", selector.select("orders", snapshots, requirement,
                ExecutorRoutingStrategy.ROUND_ROBIN, "key").orElseThrow().instanceId());
    }

    @Test
    void retriesTheNextCandidateWhenAtomicReservationRejectsAStaleSnapshot() {
        List<ExecutorResourceSnapshot> snapshots = List.of(
                new ExecutorResourceSnapshot("orders", "orders-1", 1000, 2048, Set.of(), 0, 0),
                new ExecutorResourceSnapshot("orders", "orders-2", 1000, 2048, Set.of(), 0, 0));
        ExecutorResourceRequirement requirement = ExecutorResourceRequirement.defaults("tenant-1");

        var selected = new ResourceAwareExecutorSelector().selectAndReserve(
                "orders", snapshots, requirement, ExecutorRoutingStrategy.ROUND_ROBIN, "key",
                (snapshot, ignored) -> snapshot.instanceId().equals("orders-2")
        );

        assertEquals("orders-2", selected.orElseThrow().instanceId());
    }
}
