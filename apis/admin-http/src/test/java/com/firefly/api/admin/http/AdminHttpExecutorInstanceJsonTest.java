package com.firefly.api.admin.http;

import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorInstanceStatus;
import com.firefly.domain.ExecutorProtocol;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpExecutorInstanceJsonTest {
    @Test
    void exposesInstanceLifecycleAndHeartbeatDetails() {
        Instant now = Instant.parse("2026-07-20T12:00:00Z");
        ExecutorInstance instance = ExecutorInstance.builder()
                .executorName("billing-executor")
                .instanceId("billing-1")
                .sessionId("session-1")
                .gatewayNodeId("gateway-a")
                .serviceName("billing-service")
                .host("10.0.0.8")
                .protocol(ExecutorProtocol.TCP)
                .registeredAt(now.minusSeconds(20))
                .lastHeartbeatAt(now.minusSeconds(4))
                .status(ExecutorInstanceStatus.ONLINE)
                .metadata(Map.of("version", "1.2.0", "handlerNames", "billingHandler,refundHandler"))
                .build();

        String json = AdminHttpJson.executors(List.of(), List.of(instance), now, Duration.ofSeconds(30));

        assertTrue(json.contains("\"registeredAt\":\"2026-07-20T11:59:40Z\""));
        assertTrue(json.contains("\"heartbeatAgeSeconds\":4"));
        assertTrue(json.contains("\"heartbeatTimeoutSeconds\":30"));
        assertTrue(json.contains("\"sessionId\":\"session-1\""));
        assertTrue(json.contains("\"gatewayNodeId\":\"gateway-a\""));
        assertTrue(json.contains("\"handlers\":[\"billingHandler\",\"refundHandler\"]"));
        assertTrue(json.contains("\"handlerNames\":\"billingHandler,refundHandler\""));
        assertTrue(json.contains("\"version\":\"1.2.0\""));
    }
}
