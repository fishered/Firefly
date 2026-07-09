package com.firefly.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerOptionsTest {
    @Test
    void disablesOptionalPluginsByDefault() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of());

        assertFalse(options.demoEnabled());
        assertFalse(options.adminWebEnabled());
        assertFalse(options.prometheusMetricsEnabled());
        assertEquals(9710, options.adminWebPort());
        assertEquals(9711, options.prometheusMetricsPort());
    }

    @Test
    void enablesPluginsFromCommandLineFlags() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.demo.enabled=true",
                "--firefly.admin-web.enabled=true",
                "--firefly.admin-web.port=9810",
                "--firefly.metrics.prometheus.enabled=true",
                "--firefly.metrics.prometheus.port=9811"
        }, Map.of());

        assertTrue(options.demoEnabled());
        assertTrue(options.adminWebEnabled());
        assertEquals(9810, options.adminWebPort());
        assertTrue(options.prometheusMetricsEnabled());
        assertEquals(9811, options.prometheusMetricsPort());
    }

    @Test
    void readsEnvironmentWhenFlagsAreAbsent() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of(
                "FIREFLY_DEMO_ENABLED", "true",
                "FIREFLY_ADMIN_WEB_ENABLED", "true",
                "FIREFLY_METRICS_PROMETHEUS_ENABLED", "true"
        ));

        assertTrue(options.demoEnabled());
        assertTrue(options.adminWebEnabled());
        assertTrue(options.prometheusMetricsEnabled());
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"--firefly.admin-web.port=0"}, Map.of())
        );
    }
}
