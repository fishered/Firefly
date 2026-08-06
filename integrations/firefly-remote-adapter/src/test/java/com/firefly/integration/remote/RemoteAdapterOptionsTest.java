package com.firefly.integration.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteAdapterOptionsTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty("firefly.executor.name");
        System.clearProperty("firefly.executor.gateway-addresses");
        System.clearProperty("firefly.executor.heartbeat-interval");
    }

    @Test
    void loadsTheSameExecutorPropertyNamespaceWithoutSpring() {
        System.setProperty("firefly.executor.name", "billing-executor");
        System.setProperty("firefly.executor.gateway-addresses", "firefly-a:9700, firefly-b:9700");
        System.setProperty("firefly.executor.heartbeat-interval", "5s");

        RemoteAdapterOptions options = RemoteAdapterOptions.fromEnvironment();

        assertEquals("billing-executor", options.executorName());
        assertEquals("billing-executor", options.serviceName());
        assertEquals(java.util.List.of("firefly-a:9700", "firefly-b:9700"), options.gatewayAddresses());
        assertEquals(Duration.ofSeconds(5), options.heartbeatInterval());
    }

    @Test
    void requiresAFixedExecutorName() {
        assertThrows(IllegalArgumentException.class, RemoteAdapterOptions::fromEnvironment);
    }
}
