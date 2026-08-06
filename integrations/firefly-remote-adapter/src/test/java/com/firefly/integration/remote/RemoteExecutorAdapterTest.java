package com.firefly.integration.remote;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.executor.netty.NettyExecutorConnectionRegistry;
import com.firefly.executor.netty.NettyExecutorGateway;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteExecutorAdapterTest {
    @Test
    void registersFixedHandlersAndExecutesControlPlaneDispatch() throws Exception {
        int port = freePort();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name("billing-executor")
                .protocols(Set.of(ExecutorProtocol.TCP))
                .build());
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                port, new InMemoryExecutorRegistry(), new NettyExecutorConnectionRegistry(),
                Clock.systemUTC(), catalog, true
        );
        CountDownLatch handled = new CountDownLatch(1);

        gateway.start();
        try (gateway; RemoteExecutorAdapter adapter = RemoteExecutorAdapter.create(
                options(port, "billing-executor"),
                handlers -> handlers.bind("billing", context -> handled.countDown())
        )) {
            adapter.start();

            assertTrue(adapter.isReady());
            assertEquals(Set.of("billing"), adapter.handlerNames());
            assertTrue(gateway.dispatch("billing-executor", "billing", context("billing")));
            assertTrue(handled.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void dispatchesToAnAnnotatedMethodOnAnExplicitObject() throws Exception {
        int port = freePort();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name("billing-executor")
                .protocols(Set.of(ExecutorProtocol.TCP))
                .build());
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                port, new InMemoryExecutorRegistry(), new NettyExecutorConnectionRegistry(),
                Clock.systemUTC(), catalog, true
        );
        AnnotatedBillingHandlers handlers = new AnnotatedBillingHandlers();

        gateway.start();
        try (gateway; RemoteExecutorAdapter adapter = RemoteExecutorAdapter.create(
                options(port, "billing-executor"), RemoteHandlerProvider.annotated(handlers)
        )) {
            adapter.start();

            assertTrue(gateway.dispatch("billing-executor", "annotated-billing", context("annotated-billing")));
            assertTrue(handlers.handled.await(3, TimeUnit.SECONDS));
            assertEquals("execution-a", handlers.executionId.get());
        }
    }

    @Test
    void rejectsUnknownExecutorWithoutCreatingItsDefinition() throws Exception {
        int port = freePort();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                port, new InMemoryExecutorRegistry(), new NettyExecutorConnectionRegistry(),
                Clock.systemUTC(), catalog, true
        );

        gateway.start();
        try (gateway; RemoteExecutorAdapter adapter = RemoteExecutorAdapter.create(
                options(port, "missing-executor"),
                handlers -> handlers.bind("billing", context -> { })
        )) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, adapter::start);
            assertTrue(failure.getMessage().contains("registration was rejected"));
            assertTrue(catalog.findExecutor("missing-executor").isEmpty());
        }
    }

    @Test
    void rejectsEmptyAndDuplicateHandlerCatalogsBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> RemoteExecutorAdapter.create(
                options(9700, "billing-executor"), handlers -> { }
        ));
        assertThrows(IllegalArgumentException.class, () -> RemoteExecutorAdapter.create(
                options(9700, "billing-executor"), handlers -> {
                    handlers.bind("billing", context -> { });
                    handlers.bind("billing", context -> { });
                }
        ));
    }

    private RemoteAdapterOptions options(int port, String executorName) {
        return RemoteAdapterOptions.builder()
                .schedulerHost("127.0.0.1")
                .schedulerPort(port)
                .executorName(executorName)
                .instanceId("instance-a")
                .serviceName("billing-service")
                .startupTimeout(Duration.ofSeconds(3))
                .build();
    }

    private ExecutionContext context(String handlerName) {
        Instant now = Instant.now();
        return new ExecutionContext(
                "execution-a", "job-a", handlerName, now, now, now, Map.of()
        );
    }

    private int freePort() throws java.io.IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static class AnnotatedBillingHandlers {
        private final CountDownLatch handled = new CountDownLatch(1);
        private final AtomicReference<String> executionId = new AtomicReference<>();

        @FireflyHandler(handlerName = "annotated-billing")
        private void bill(ExecutionContext context) {
            executionId.set(context.executionId());
            handled.countDown();
        }
    }
}
