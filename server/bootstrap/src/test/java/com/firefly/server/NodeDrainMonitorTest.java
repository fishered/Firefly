package com.firefly.server;

import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.InMemoryNodeRegistry;
import com.firefly.cluster.InMemoryShardManager;
import com.firefly.cluster.NodeRole;
import com.firefly.cluster.NodeStatus;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDrainMonitorTest {
    @Test
    void waitsForActiveTargetsThenMarksTheLocalNodeOffline() {
        Instant now = Instant.parse("2026-07-20T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryNodeRegistry nodes = new InMemoryNodeRegistry();
        nodes.register(FireflyNode.builder().nodeId("node-a").roles(Set.of(NodeRole.GATEWAY))
                .registeredAt(now).lastHeartbeatAt(now).build());
        nodes.markDraining("node-a");
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "exec-1", "exec-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.RUNNING,
                1, 1, "scheduler-a", 1L, now, now
        ));
        executions.saveTargets(List.of(new ExecutionTargetRecord(
                "target-1", "exec-1", "instance-1", "node-a", null,
                ExecutionStatus.RUNNING, 1, now, null, "", now, now
        )));
        NodeDrainMonitor monitor = new NodeDrainMonitor(
                "node-a", nodes, new InMemoryShardManager(), new InMemoryJobRepository(clock),
                executions, null, clock
        );

        assertFalse(monitor.status("node-a").readyForOffline());
        monitor.check();
        assertEquals(NodeStatus.DRAINING, nodes.find("node-a").orElseThrow().status());

        executions.completeResult("target-1", ExecutionStatus.SUCCEEDED, "", now);
        assertTrue(monitor.status("node-a").readyForOffline());
        monitor.check();
        assertEquals(NodeStatus.OFFLINE, nodes.find("node-a").orElseThrow().status());
        monitor.close();
    }
}
