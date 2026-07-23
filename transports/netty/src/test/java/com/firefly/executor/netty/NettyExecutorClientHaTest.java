package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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
                .build()
                .registerHandler("refundHandler", context -> { })
                .registerHandler("billingHandler", context -> { });
        try (gateway) {
            client.start();
            await(() -> gateway.executorRegistry()
                    .find("billing-executor", "billing-instance-1")
                    .map(instance -> instance.status() == com.firefly.domain.ExecutorInstanceStatus.ONLINE)
                    .orElse(false), Duration.ofSeconds(3));

            assertEquals(
                    "billingHandler,refundHandler",
                    gateway.executorRegistry().find("billing-executor", "billing-instance-1")
                            .orElseThrow().metadata().get("handlerNames")
            );

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

    @Test
    void repeatedRegistrationRejectionWarnsOnceAndKeepsBackoffState() throws Exception {
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
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(NettyExecutorClient.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousParentHandlers = logger.getUseParentHandlers();
        CopyOnWriteArrayList<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        recorder.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(recorder);

        gateway.start();
        try (gateway;
             NettyExecutorClient client = NettyExecutorClient.builder()
                     .gatewayAddresses(List.of("127.0.0.1:" + gatewayPort))
                     .executorName("billing-executor")
                     .serviceName("billing-service")
                     .authToken("wrong-token")
                     .reconnectInitialDelay(Duration.ofMillis(10))
                     .reconnectMaxDelay(Duration.ofMillis(40))
                     .build()) {
            client.start();
            await(() -> records.stream().anyMatch(record -> record.getMessage().contains("attempt=2")),
                    Duration.ofSeconds(3));

            long warnings = records.stream()
                    .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                    .filter(record -> record.getMessage().contains("executor registration rejected"))
                    .count();
            assertEquals(1, warnings);
            assertEquals(0, client.connectedGatewayCount());
        } finally {
            logger.removeHandler(recorder);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParentHandlers);
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
