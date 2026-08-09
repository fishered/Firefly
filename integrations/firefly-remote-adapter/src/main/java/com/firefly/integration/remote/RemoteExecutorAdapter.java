package com.firefly.integration.remote;

import com.firefly.executor.netty.ExecutorDefinitionRegistrationPolicy;
import com.firefly.executor.netty.FileExecutorResultStore;
import com.firefly.executor.netty.InMemoryExecutorResultStore;
import com.firefly.executor.netty.NettyExecutorClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Framework-neutral business-side adapter. It registers a fixed executor and
 * its handlers, then waits for the Gateway to accept the registration. Job
 * definitions and schedules remain managed by Firefly's control plane.
 */
public final class RemoteExecutorAdapter implements AutoCloseable {
    private enum State { NEW, STARTED, READY, STOPPED, FAILED }

    private final RemoteAdapterOptions options;
    private final RemoteHandlerRegistry handlers;
    private final NettyExecutorClient client;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private RemoteExecutorAdapter(RemoteAdapterOptions options, RemoteHandlerProvider provider) {
        this.options = Objects.requireNonNull(options, "options");
        this.handlers = new RemoteHandlerRegistry();
        Objects.requireNonNull(provider, "provider").register(handlers);
        if (handlers.names().isEmpty()) {
            throw new IllegalArgumentException("at least one remote handler must be registered");
        }
        var resultStore = options.idempotencyDirectory() == null
                ? new InMemoryExecutorResultStore()
                : new FileExecutorResultStore(
                        options.idempotencyDirectory(), options.idempotencyRetention()
                );
        this.client = NettyExecutorClient.builder()
                .schedulerHost(options.schedulerHost())
                .schedulerPort(options.schedulerPort())
                .gatewayAddresses(options.gatewayAddresses())
                .executorName(options.executorName())
                .instanceId(options.instanceId())
                .serviceName(options.serviceName())
                .heartbeatInterval(options.heartbeatInterval())
                .reconnectInitialDelay(options.reconnectInitialDelay())
                .reconnectMaxDelay(options.reconnectMaxDelay())
                .authToken(options.integrationKey())
                .definitionRegistrationPolicy(ExecutorDefinitionRegistrationPolicy.REQUIRE_EXISTING)
                .tlsOptions(options.tlsOptions().toNetty())
                .resultStore(resultStore)
                .build();
        handlers.registerInto(client);
    }

    public static RemoteExecutorAdapter create(RemoteAdapterOptions options, RemoteHandlerProvider provider) {
        return new RemoteExecutorAdapter(options, provider);
    }

    public static void run(RemoteHandlerProvider provider) throws InterruptedException {
        try (RemoteExecutorAdapter adapter = create(RemoteAdapterOptions.fromEnvironment(), provider)) {
            Thread shutdownHook = new Thread(adapter::close, "firefly-remote-adapter-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            adapter.start();
            try {
                adapter.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shutdownInProgress) {
                    // The registered hook owns shutdown once JVM termination has started.
                }
            }
        }
    }

    public void start() throws InterruptedException {
        if (!state.compareAndSet(State.NEW, State.STARTED)) return;
        try {
            client.start();
            waitUntilReady(options.startupTimeout());
            state.set(State.READY);
        } catch (InterruptedException e) {
            state.set(State.FAILED);
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            state.set(State.FAILED);
            client.close();
            throw e;
        }
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    public boolean isReady() {
        return state.get() == State.READY && client.connectedGatewayCount() > 0;
    }

    public String executorName() { return options.executorName(); }
    public java.util.Set<String> handlerNames() { return handlers.names(); }
    public Map<String, String> registrationFailures() { return client.registrationFailures(); }

    @Override
    public void close() {
        State previous = state.getAndSet(State.STOPPED);
        if (previous == State.STOPPED) return;
        try {
            client.close();
        } finally {
            terminated.countDown();
        }
    }

    private void waitUntilReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (client.connectedGatewayCount() == 0) {
            Map<String, String> failures = client.registrationFailures();
            int expectedGateways = options.gatewayAddresses().isEmpty()
                    ? 1 : options.gatewayAddresses().stream().distinct().toList().size();
            if (failures.size() >= expectedGateways) {
                throw new IllegalStateException("Firefly executor registration was rejected: executor="
                        + options.executorName() + ", failures=" + failures);
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Firefly executor registration did not become ready: executor="
                        + options.executorName() + ", failures=" + client.registrationFailures());
            }
            Thread.sleep(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(
                    deadline - System.nanoTime()
            ))));
        }
    }
}
