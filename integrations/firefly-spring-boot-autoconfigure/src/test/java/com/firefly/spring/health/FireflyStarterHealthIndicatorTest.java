package com.firefly.spring.health;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.executor.netty.NettyExecutorConnectionRegistry;
import com.firefly.executor.netty.NettyExecutorGateway;
import com.firefly.spring.job.FireflyJobRegistrationProperties;
import com.firefly.spring.netty.FireflyNettyExecutorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyStarterHealthIndicatorTest {
    @Test
    void reportsDownWhenAutoStartedExecutorHasNoRegisteredGateways() {
        NettyExecutorClient client = client();
        try {
            FireflyStarterHealthIndicator indicator = new FireflyStarterHealthIndicator(
                    client,
                    executorProperties(true),
                    new FireflyJobRegistrationProperties(),
                    new FireflyStarterHealthState()
            );

            Health health = indicator.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertTrue(issues(health).contains("no registered gateway connections"));
        } finally {
            client.close();
        }
    }

    @Test
    void reportsUpWithoutGatewaysWhenAutoStartIsDisabled() {
        NettyExecutorClient client = client();
        try {
            FireflyStarterHealthIndicator indicator = new FireflyStarterHealthIndicator(
                    client,
                    executorProperties(false),
                    new FireflyJobRegistrationProperties(),
                    new FireflyStarterHealthState()
            );

            assertEquals(Status.UP, indicator.health().getStatus());
        } finally {
            client.close();
        }
    }

    @Test
    void reportsDownWhenJobRegistrationFailedEvenIfStartupDidNotFailFast() {
        NettyExecutorClient client = client();
        try {
            FireflyStarterHealthState healthState = new FireflyStarterHealthState();
            healthState.jobRegistrationFailed(0, List.of("billing-daily: HTTP 401"));
            FireflyStarterHealthIndicator indicator = new FireflyStarterHealthIndicator(
                    client,
                    executorProperties(false),
                    new FireflyJobRegistrationProperties(),
                    healthState
            );

            Health health = indicator.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertTrue(issues(health).contains("job registration failed"));
            assertTrue(health.getDetails().get("jobRegistration").toString().contains("billing-daily"));
        } finally {
            client.close();
        }
    }

    @Test
    void reportsDownWhenGatewayRejectsExecutorRegistration() throws Exception {
        int gatewayPort = freePort();
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                gatewayPort,
                new com.firefly.executor.InMemoryExecutorRegistry(),
                new NettyExecutorConnectionRegistry(),
                Clock.systemUTC(),
                new com.firefly.catalog.InMemorySchedulerCatalog(),
                true,
                "local",
                new com.firefly.execution.InMemoryExecutionRepository(),
                (executionId, acknowledgedAt) -> { },
                "expected-token"
        );
        NettyExecutorClient client = NettyExecutorClient.builder()
                .gatewayAddresses(List.of("127.0.0.1:" + gatewayPort))
                .executorName("billing-executor")
                .serviceName("billing-service")
                .authToken("wrong-token")
                .reconnectInitialDelay(Duration.ofMillis(10))
                .reconnectMaxDelay(Duration.ofMillis(40))
                .build();
        gateway.start();
        try (gateway; client) {
            client.start();
            await(() -> !client.registrationFailures().isEmpty(), Duration.ofSeconds(3));

            Health health = new FireflyStarterHealthIndicator(
                    client,
                    executorProperties(true),
                    new FireflyJobRegistrationProperties(),
                    new FireflyStarterHealthState()
            ).health();

            assertEquals(Status.DOWN, health.getStatus());
            assertTrue(issues(health).contains("executor registration rejected"));
            assertTrue(health.getDetails().get("registrationFailures").toString()
                    .contains("executor authentication failed"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> issues(Health health) {
        return (List<String>) health.getDetails().get("issues");
    }

    private FireflyNettyExecutorProperties executorProperties(boolean autoStart) {
        FireflyNettyExecutorProperties properties = new FireflyNettyExecutorProperties();
        properties.setName("billing-executor");
        properties.setAutoStart(autoStart);
        return properties;
    }

    private NettyExecutorClient client() {
        return NettyExecutorClient.builder()
                .executorName("billing-executor")
                .serviceName("billing-service")
                .build();
    }

    private void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition not met before timeout");
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
