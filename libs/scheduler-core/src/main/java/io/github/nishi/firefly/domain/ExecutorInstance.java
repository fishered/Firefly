package io.github.nishi.firefly.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one live service instance registered under a logical executor.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record ExecutorInstance(
        String executorName,
        String instanceId,
        String serviceName,
        String host,
        int port,
        ExecutorProtocol protocol,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        ExecutorInstanceStatus status,
        Map<String, String> metadata
) {
    public ExecutorInstance {
        Objects.requireNonNull(executorName, "executorName");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        Objects.requireNonNull(status, "status");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must not be blank");
        }
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }

    /**
     * Keeps local embedded executors easy to register while remote modules can override protocol and port.
     */
    public static Builder builder() {
        return newBuilder()
                .host("localhost")
                .port(0)
                .protocol(ExecutorProtocol.EMBEDDED)
                .status(ExecutorInstanceStatus.ONLINE)
                .metadata(Map.of());
    }
}
