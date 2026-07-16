package com.firefly.api.admin.model;

public record AdminNodeView(
        String nodeId,
        String roles,
        String status,
        String lastHeartbeatAt
) {
}
