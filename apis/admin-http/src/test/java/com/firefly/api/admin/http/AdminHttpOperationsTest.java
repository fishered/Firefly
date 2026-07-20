package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.InMemoryNodeRegistry;
import com.firefly.cluster.NodeRole;
import com.firefly.cluster.NodeStatus;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPluginContext;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpOperationsTest {
    @Test
    void drainsNodesAndIsolatesExecutorDefinitions() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        InMemoryNodeRegistry nodes = new InMemoryNodeRegistry();
        nodes.register(FireflyNode.builder().nodeId("node-a").roles(Set.of(NodeRole.SCHEDULER))
                .registeredAt(now).lastHeartbeatAt(now).build());
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(ExecutorDefinition.builder().name("orders")
                .protocols(Set.of(ExecutorProtocol.TCP)).build());
        AtomicInteger isolated = new AtomicInteger();
        AtomicBoolean drainReady = new AtomicBoolean();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .nodeRegistry(nodes)
                .schedulerCatalog(catalog)
                .executorIsolationDispatcher(ignored -> {
                    isolated.incrementAndGet();
                    return com.firefly.executor.ExecutorIsolationResult.local(2);
                })
                .nodeDrainStatusProvider(nodeId -> new com.firefly.plugin.NodeDrainStatus(
                        nodeId, nodes.find(nodeId).orElseThrow().status(),
                        drainReady.get() ? 0 : 1, 0, 0, 2, drainReady.get()
                ))
                .build());
        try {
            assertEquals(202, post(port, "/api/nodes/node-a/drain").statusCode());
            assertEquals(NodeStatus.DRAINING, nodes.find("node-a").orElseThrow().status());
            assertEquals(409, post(port, "/api/nodes/node-a/offline").statusCode());
            assertTrue(get(port, "/api/nodes/node-a/drain-status").body().contains("\"ownedShards\":1"));
            drainReady.set(true);
            assertEquals(202, post(port, "/api/nodes/node-a/offline").statusCode());

            HttpResponse<String> isolate = post(port, "/api/executor-definitions/orders/isolate");
            assertEquals(202, isolate.statusCode());
            assertFalse(catalog.findExecutor("orders").orElseThrow().enabled());
            assertEquals(1, isolated.get());
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> post(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
