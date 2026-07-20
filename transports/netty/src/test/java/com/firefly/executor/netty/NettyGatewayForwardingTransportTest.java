package com.firefly.executor.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyGatewayForwardingTransportTest {
    @Test
    void forwardsAPlannedFrameToTheGatewayHoldingTheFencedSession() throws Exception {
        int port = freePort();
        NettyExecutorConnectionRegistry remoteConnections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel executorChannel = new EmbeddedChannel();
        remoteConnections.register(
                "orders", "orders-1", "session-1", 2,
                Set.of("TARGET_ACK", "RESULT_REPORT", "CANCELLATION"), executorChannel
        );
        NettyExecutorGatewayOptions serverOptions = options(port, "cluster-secret");
        NettyGatewayForwardingTransport server = new NettyGatewayForwardingTransport(
                remoteConnections, serverOptions
        );
        com.firefly.metrics.SchedulerMetrics metrics = new com.firefly.metrics.SchedulerMetrics();
        NettyGatewayForwardingTransport client = new NettyGatewayForwardingTransport(
                new NettyExecutorConnectionRegistry(), options(0, "cluster-secret"), metrics
        );
        server.start();
        try {
            String frame = new NettyExecutorJsonCodec().encode(new NettyExecutorMessage(
                    "message-1", NettyExecutorMessageType.TRIGGER_JOB, java.util.Map.of("executionId", "exec-1")
            ));
            assertTrue(client.forward(
                    "http://127.0.0.1:" + port, "orders", "orders-1", "session-1", frame
            ));
            assertNotNull(executorChannel.readOutbound());
            assertFalse(client.forward(
                    "http://127.0.0.1:" + port, "orders", "orders-1", "stale-session", frame
            ));
            assertTrue(metrics.snapshot().gatewayForwardAttempts() == 2);
            assertTrue(metrics.snapshot().gatewayForwardSuccesses() == 1);
            assertTrue(metrics.snapshot().gatewayForwardFailures() == 1);
        } finally {
            client.close();
            server.close();
            executorChannel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsUnsignedAndOversizedInternalRequests() throws Exception {
        int port = freePort();
        NettyGatewayForwardingTransport server = new NettyGatewayForwardingTransport(
                new NettyExecutorConnectionRegistry(), options(port, "cluster-secret")
        );
        server.start();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpResponse<Void> unsigned = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/internal/executor/dispatch"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()
            );
            assertTrue(unsigned.statusCode() == 403);

            byte[] oversized = new byte[70_000];
            java.net.http.HttpResponse<Void> tooLarge = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/internal/executor/dispatch"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(oversized))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()
            );
            assertTrue(tooLarge.statusCode() == 413);
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsReplayOfAnOtherwiseValidSignedRequest() throws Exception {
        int port = freePort();
        String token = "cluster-secret";
        NettyGatewayForwardingTransport server = new NettyGatewayForwardingTransport(
                new NettyExecutorConnectionRegistry(), options(port, token)
        );
        server.start();
        try {
            String path = "/internal/executor/dispatch";
            byte[] body = "{\"executorName\":\"orders\",\"instanceId\":\"missing\","
                    .concat("\"sessionId\":\"session\",\"frame\":\"{}\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String timestamp = Long.toString(java.time.Instant.now().toEpochMilli());
            String nonce = java.util.UUID.randomUUID().toString();
            String signature = signature(token, path, timestamp, nonce, body);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + port + path))
                    .header("X-Firefly-Timestamp", timestamp)
                    .header("X-Firefly-Nonce", nonce)
                    .header("X-Firefly-Signature", signature)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

            assertTrue(client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 404);
            assertTrue(client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 403);
        } finally {
            server.close();
        }
    }

    @Test
    void gatewayDispatchesThroughTheSharedDirectoryToARemoteGateway() throws Exception {
        int port = freePort();
        String token = "cluster-secret";
        NettyExecutorConnectionRegistry remoteConnections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel executorChannel = new EmbeddedChannel();
        remoteConnections.register(
                "orders", "orders-1", "session-1", 2,
                Set.of("TARGET_ACK", "RESULT_REPORT", "CANCELLATION"), executorChannel
        );
        NettyGatewayForwardingTransport remoteServer = new NettyGatewayForwardingTransport(
                remoteConnections, options(port, token)
        );
        remoteServer.start();
        var directory = new com.firefly.executor.InMemoryExecutorInstanceDirectory();
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        directory.register(new com.firefly.executor.ExecutorInstanceLocation(
                "orders", "orders-1", "gateway-b", "http://127.0.0.1:" + port,
                "session-1", now, now.plusSeconds(90), java.util.Map.of()
        ));
        var executions = new com.firefly.execution.InMemoryExecutionRepository();
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                0, new com.firefly.executor.InMemoryExecutorRegistry(),
                new NettyExecutorConnectionRegistry(), Clock.fixed(now, ZoneOffset.UTC),
                new com.firefly.catalog.InMemorySchedulerCatalog(), true, "gateway-a", executions,
                (executionId, acknowledgedAt) -> { }, "", (executionId, timeout) -> { },
                new com.firefly.metrics.SchedulerMetrics(), options(0, token), directory
        );
        try {
            var context = new com.firefly.domain.ExecutionContext(
                    "exec-1", "job-1", "handler", now, now, now, java.util.Map.of()
            );
            var result = gateway.dispatch(new com.firefly.executor.RemoteDispatchRequest(
                    "orders", "handler", context,
                    com.firefly.domain.ExecutorDispatchMode.UNICAST,
                    com.firefly.domain.ExecutorRoutingStrategy.ROUND_ROBIN,
                    com.firefly.domain.ExecutorCompletionPolicy.ALL_SUCCESS, 1, "order-1"
            ));

            assertTrue(result.accepted());
            assertNotNull(executorChannel.readOutbound());
            assertTrue(executions.listTargets("exec-1").stream()
                    .allMatch(target -> target.gatewayNodeId().equals("gateway-b")));
        } finally {
            gateway.close();
            remoteServer.close();
            executorChannel.finishAndReleaseAll();
        }
    }

    private NettyExecutorGatewayOptions options(int port, String token) {
        return new NettyExecutorGatewayOptions(
                100, 64 * 1024, NettyTlsOptions.disabled(), Duration.ofSeconds(30),
                "127.0.0.1", port, port == 0 ? "" : "http://127.0.0.1:" + port,
                token, Duration.ofSeconds(30), Duration.ofSeconds(90)
        );
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String signature(String token, String path, String timestamp, String nonce, byte[] body) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
        String canonical = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                + java.util.Base64.getEncoder().encodeToString(digest);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"
        ));
        return java.util.Base64.getEncoder().encodeToString(
                mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
