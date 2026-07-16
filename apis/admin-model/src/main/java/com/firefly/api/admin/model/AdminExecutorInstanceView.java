package com.firefly.api.admin.model;

public record AdminExecutorInstanceView(
        String executorName,
        String instanceId,
        String serviceName,
        String host,
        int port,
        String protocol,
        String status,
        String lastHeartbeatAt,
        long heartbeatAgeSeconds
) {
}
