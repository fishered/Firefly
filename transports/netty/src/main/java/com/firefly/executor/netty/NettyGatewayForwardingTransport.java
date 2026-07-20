package com.firefly.executor.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated Gateway-to-Gateway hop for an already planned target frame. */
final class NettyGatewayForwardingTransport implements AutoCloseable {
    private final NettyExecutorConnectionRegistry connections;
    private final NettyExecutorGatewayOptions options;
    private final com.firefly.metrics.SchedulerMetrics metrics;
    private final ConcurrentHashMap<String, Long> acceptedNonces = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private HttpServer server;

    NettyGatewayForwardingTransport(
            NettyExecutorConnectionRegistry connections,
            NettyExecutorGatewayOptions options
    ) {
        this(connections, options, new com.firefly.metrics.SchedulerMetrics());
    }

    NettyGatewayForwardingTransport(
            NettyExecutorConnectionRegistry connections,
            NettyExecutorGatewayOptions options,
            com.firefly.metrics.SchedulerMetrics metrics
    ) {
        this.connections = connections;
        this.options = options;
        this.metrics = metrics;
    }

    void start() {
        if (options.internalForwardPort() == 0) return;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    options.internalForwardHost(), options.internalForwardPort()
            ), 0);
            server.createContext("/internal/executor/dispatch", this::handle);
            server.createContext("/internal/executor/isolate", this::handleIsolate);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start Gateway forwarding endpoint", e);
        }
    }

    boolean forward(String address, String executorName, String instanceId, String sessionId, String frame) {
        if (address == null || address.isBlank()) return false;
        try {
            byte[] body = mapper.writeValueAsBytes(new ForwardRequest(
                    executorName, instanceId, sessionId, frame
            ));
            return post(address, "/internal/executor/dispatch", body);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isolate(String address, String executorName) {
        if (address == null || address.isBlank()) return false;
        try {
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("executorName", executorName));
            return post(address, "/internal/executor/isolate", body);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean post(String address, String path, byte[] body) {
        long startedAt = System.nanoTime();
        boolean success = false;
        try {
            String timestamp = Long.toString(Instant.now().toEpochMilli());
            String nonce = UUID.randomUUID().toString();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripSlash(address) + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Firefly-Timestamp", timestamp)
                    .header("X-Firefly-Nonce", nonce)
                    .header("X-Firefly-Signature", signature(path, timestamp, nonce, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            success = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 202;
            return success;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            metrics.recordGatewayForward(Duration.ofNanos(System.nanoTime() - startedAt), success);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405);
            return;
        }
        byte[] body = readLimitedBody(exchange);
        if (body == null) return;
        if (!authorized(exchange, body)) {
            respond(exchange, 403);
            return;
        }
        ForwardRequest request;
        try {
            request = mapper.readValue(body, ForwardRequest.class);
        } catch (Exception invalid) {
            respond(exchange, 400);
            return;
        }
        var target = connections.find(request.executorName(), request.instanceId())
                .filter(connection -> connection.sessionId().equals(request.sessionId()));
        if (target.isEmpty()) {
            respond(exchange, 404);
            return;
        }
        NettyExecutorMessage forwarded;
        try {
            forwarded = new NettyExecutorJsonCodec().decode(request.frame());
        } catch (RuntimeException invalid) {
            respond(exchange, 400);
            return;
        }
        if (forwarded.type() == NettyExecutorMessageType.CANCEL_JOB
                && !target.get().supports("CANCELLATION")) {
            respond(exchange, 409);
            return;
        }
        target.get().channel().writeAndFlush(request.frame() + "\n");
        respond(exchange, 202);
    }

    private void handleIsolate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405);
            return;
        }
        byte[] body = readLimitedBody(exchange);
        if (body == null) return;
        if (!authorized(exchange, body)) {
            respond(exchange, 403);
            return;
        }
        java.util.Map<?, ?> request;
        try {
            request = mapper.readValue(body, java.util.Map.class);
        } catch (Exception invalid) {
            respond(exchange, 400);
            return;
        }
        Object executorName = request.get("executorName");
        if (!(executorName instanceof String value) || value.isBlank()) {
            respond(exchange, 400);
            return;
        }
        connections.closeExecutor(value);
        respond(exchange, 202);
    }

    private boolean authorized(HttpExchange exchange, byte[] body) {
        String timestamp = exchange.getRequestHeaders().getFirst("X-Firefly-Timestamp");
        String nonce = exchange.getRequestHeaders().getFirst("X-Firefly-Nonce");
        String provided = exchange.getRequestHeaders().getFirst("X-Firefly-Signature");
        if (timestamp == null || nonce == null || provided == null) return false;
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            return false;
        }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - timestampMillis) > Duration.ofSeconds(30).toMillis()) return false;
        String expected = signature(exchange.getRequestURI().getPath(), timestamp, nonce, body);
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII)
        )) return false;
        if (acceptedNonces.putIfAbsent(nonce, timestampMillis) != null) return false;
        if (acceptedNonces.size() > 10_000) {
            acceptedNonces.entrySet().removeIf(entry -> entry.getValue() < now - Duration.ofMinutes(1).toMillis());
        }
        return true;
    }

    private String signature(String path, String timestamp, String nonce, byte[] body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            String canonical = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                    + Base64.getEncoder().encodeToString(digest);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    options.internalAuthToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            ));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign Gateway forwarding request", e);
        }
    }

    private byte[] readLimitedBody(HttpExchange exchange) throws IOException {
        int limit = options.maxFrameLength() + 4096;
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > limit) {
                    respond(exchange, 413);
                    return null;
                }
            } catch (NumberFormatException invalid) {
                respond(exchange, 400);
                return null;
            }
        }
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
        if (body.length > limit) {
            respond(exchange, 413);
            return null;
        }
        return body;
    }

    private void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
    }

    private record ForwardRequest(String executorName, String instanceId, String sessionId, String frame) {
    }
}
