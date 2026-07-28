package com.firefly.benchmark.process;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class BenchmarkHttpClient {
    private final HttpClient client;
    private final URI baseUri;
    private final Duration timeout;

    public BenchmarkHttpClient(URI baseUri, Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path, "");
    }

    public HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send("POST", path, body);
    }

    public HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return send("PUT", path, body);
    }

    public boolean awaitHealthy(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (get("/api/health").statusCode() == 200) {
                    return true;
                }
            } catch (IOException ignored) {
                // The process may still be binding the admin listener.
            }
            Thread.sleep(100);
        }
        return false;
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null || body.isBlank()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .method(method, publisher)
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
