package com.firefly.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerOptionsTest {
    @Test
    void disablesOptionalPluginsByDefault() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of());

        assertEquals(ServerNodeMode.STANDALONE, options.nodeMode());
        assertEquals(Set.of(), options.plugins());
        assertFalse(options.demoEnabled());
        assertFalse(options.adminWebEnabled());
        assertFalse(options.prometheusMetricsEnabled());
        assertFalse(options.nettyExecutorGatewayEnabled());
        assertEquals(9710, options.adminWebPort());
        assertEquals(9711, options.prometheusMetricsPort());
        assertEquals(9700, options.nettyExecutorGatewayPort());
    }

    @Test
    void enablesPluginsFromCommandLineFlags() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.demo.enabled=true",
                "--firefly.node.mode=cluster",
                "--firefly.admin-web.enabled=true",
                "--firefly.admin-web.port=9810",
                "--firefly.metrics.prometheus.enabled=true",
                "--firefly.metrics.prometheus.port=9811",
                "--firefly.executor.gateway.netty.enabled=true",
                "--firefly.executor.gateway.netty.port=9800"
        }, Map.of());

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertTrue(options.demoEnabled());
        assertTrue(options.adminWebEnabled());
        assertEquals(9810, options.adminWebPort());
        assertTrue(options.prometheusMetricsEnabled());
        assertEquals(9811, options.prometheusMetricsPort());
        assertTrue(options.nettyExecutorGatewayEnabled());
        assertEquals(9800, options.nettyExecutorGatewayPort());
    }

    @Test
    void enablesPluginsFromPluginList() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.plugins=admin-web,metrics-prometheus"
        }, Map.of());

        assertEquals(Set.of(ServerPlugin.ADMIN_WEB, ServerPlugin.METRICS_PROMETHEUS), options.plugins());
        assertTrue(options.adminWebEnabled());
        assertTrue(options.prometheusMetricsEnabled());
    }

    @Test
    void readsEnvironmentWhenFlagsAreAbsent() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of(
                "FIREFLY_DEMO_ENABLED", "true",
                "FIREFLY_NODE_MODE", "cluster",
                "FIREFLY_PLUGINS", "admin-web",
                "FIREFLY_ADMIN_WEB_ENABLED", "true",
                "FIREFLY_METRICS_PROMETHEUS_ENABLED", "true",
                "FIREFLY_EXECUTOR_GATEWAY_NETTY_ENABLED", "true"
        ));

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertEquals(Set.of(ServerPlugin.ADMIN_WEB), options.plugins());
        assertTrue(options.demoEnabled());
        assertTrue(options.adminWebEnabled());
        assertTrue(options.prometheusMetricsEnabled());
        assertTrue(options.nettyExecutorGatewayEnabled());
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"--firefly.admin-web.port=0"}, Map.of())
        );
    }
}
