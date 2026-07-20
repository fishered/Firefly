package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorClientHaTest {
    @Test
    void connectsToEveryGatewayAndRecoversAnInitiallyUnavailableSeed() throws Exception {
        int lateGatewayPort = freePort();
        int availableGatewayPort = freePort();
        NettyExecutorGateway availableGateway = new NettyExecutorGateway(availableGatewayPort);
        availableGateway.start();

        try (availableGateway;
             NettyExecutorClient client = NettyExecutorClient.builder()
                     .gatewayAddresses(List.of(
                             "127.0.0.1:" + lateGatewayPort,
                             "127.0.0.1:" + availableGatewayPort
                     ))
                     .executorName("ha-executor")
                     .serviceName("ha-service")
                     .reconnectInitialDelay(Duration.ofMillis(25))
                     .reconnectMaxDelay(Duration.ofMillis(100))
                     .build()) {
            client.start();
            await(() -> client.connectedGatewayCount() == 1, Duration.ofSeconds(3));

            NettyExecutorGateway lateGateway = new NettyExecutorGateway(lateGatewayPort);
            lateGateway.start();
            try (lateGateway) {
                await(() -> client.connectedGatewayCount() == 2, Duration.ofSeconds(5));
                assertTrue(client.connectedGatewayCount() == 2);
            }
        }
    }

    @Test
    void gracefulCloseUnregistersTheInstanceBeforeReturning() throws Exception {
        int gatewayPort = freePort();
        NettyExecutorGateway gateway = new NettyExecutorGateway(gatewayPort);
        gateway.start();
        NettyExecutorClient client = NettyExecutorClient.builder()
                .gatewayAddresses(List.of("127.0.0.1:" + gatewayPort))
                .executorName("billing-executor")
                .instanceId("billing-instance-1")
                .serviceName("billing-service")
                .build();
        try (gateway) {
            client.start();
            await(() -> gateway.executorRegistry()
                    .find("billing-executor", "billing-instance-1")
                    .map(instance -> instance.status() == com.firefly.domain.ExecutorInstanceStatus.ONLINE)
                    .orElse(false), Duration.ofSeconds(3));

            client.close();

            assertEquals(
                    com.firefly.domain.ExecutorInstanceStatus.OFFLINE,
                    gateway.executorRegistry().find("billing-executor", "billing-instance-1")
                            .orElseThrow().status()
            );
            assertTrue(gateway.connectionRegistry().list("billing-executor").isEmpty());
        } finally {
            client.close();
        }
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
