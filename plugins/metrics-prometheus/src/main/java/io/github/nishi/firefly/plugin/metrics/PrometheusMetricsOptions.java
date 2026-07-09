package io.github.nishi.firefly.plugin.metrics;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the Prometheus-compatible metrics endpoint.
 */
public record PrometheusMetricsOptions(String host, int port, String path, Duration heartbeatTimeout) {
    public PrometheusMetricsOptions {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
    }

    public static PrometheusMetricsOptions defaults() {
        return new PrometheusMetricsOptions("127.0.0.1", 9711, "/metrics", Duration.ofSeconds(30));
    }
}
