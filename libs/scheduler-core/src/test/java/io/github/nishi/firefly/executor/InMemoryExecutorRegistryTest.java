package io.github.nishi.firefly.executor;

import io.github.nishi.firefly.domain.ExecutorInstance;
import io.github.nishi.firefly.domain.ExecutorInstanceStatus;
import io.github.nishi.firefly.domain.ExecutorProtocol;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExecutorRegistryTest {
    @Test
    void filtersOnlineInstancesByHeartbeatLease() {
        InMemoryExecutorRegistry registry = new InMemoryExecutorRegistry();
        Instant now = Instant.parse("2026-07-08T10:00:00Z");
        registry.register(instance("report-executor", "instance-a", now.minusSeconds(10)));
        registry.register(instance("report-executor", "instance-b", now.minusSeconds(120)));

        assertEquals(1, registry.listOnline("report-executor", now, Duration.ofSeconds(30)).size());

        registry.heartbeat("report-executor", "instance-b", now);
        assertEquals(2, registry.listOnline("report-executor", now, Duration.ofSeconds(30)).size());

        registry.markOffline("report-executor", "instance-a");
        assertEquals("instance-b", registry.listOnline("report-executor", now, Duration.ofSeconds(30)).getFirst().instanceId());
    }

    @Test
    void sameServiceCanRegisterUnderMultipleExecutorsWhenCapabilitiesDiffer() {
        InMemoryExecutorRegistry registry = new InMemoryExecutorRegistry();
        Instant now = Instant.parse("2026-07-08T10:00:00Z");
        registry.register(instance("billing-executor", "billing-service-1", now));
        registry.register(instance("report-executor", "billing-service-1", now));

        assertTrue(registry.find("billing-executor", "billing-service-1").isPresent());
        assertTrue(registry.find("report-executor", "billing-service-1").isPresent());
    }

    private ExecutorInstance instance(String executorName, String instanceId, Instant heartbeatAt) {
        return ExecutorInstance.builder()
                .executorName(executorName)
                .instanceId(instanceId)
                .serviceName(instanceId)
                .host("127.0.0.1")
                .port(7001)
                .protocol(ExecutorProtocol.TCP)
                .registeredAt(heartbeatAt.minusSeconds(5))
                .lastHeartbeatAt(heartbeatAt)
                .status(ExecutorInstanceStatus.ONLINE)
                .build();
    }
}
