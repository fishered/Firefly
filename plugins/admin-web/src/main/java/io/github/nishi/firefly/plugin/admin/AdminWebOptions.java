package io.github.nishi.firefly.plugin.admin;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the lightweight admin web plugin.
 */
public record AdminWebOptions(String host, int port, Duration heartbeatTimeout) {
    public AdminWebOptions {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
    }

    public static AdminWebOptions defaults() {
        return new AdminWebOptions("127.0.0.1", 9710, Duration.ofSeconds(30));
    }
}
