package com.firefly.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExternalPluginBootstrapTest {
    @BeforeEach
    void reset() {
        ClasspathTestPlugin.STARTED.set(0);
        ClasspathTestPlugin.CLOSED.set(0);
        ClasspathTestPlugin.configuredValue = null;
        FutureApiClasspathTestPlugin.STARTED.set(0);
    }

    @Test
    void enablesClasspathPluginByConfiguredIdAndClosesIt() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.node.roles=scheduler",
                "--firefly.plugins=classpath-test",
                "--firefly.plugin.classpath-test.value=ready"
        }, Map.of());

        try (FireflyBootstrap ignored = FireflyBootstrap.start(options)) {
            assertEquals(1, ClasspathTestPlugin.STARTED.get());
            assertEquals("ready", ClasspathTestPlugin.configuredValue);
        }

        assertEquals(1, ClasspathTestPlugin.CLOSED.get());
    }

    @Test
    void rejectsConfiguredPluginThatCannotBeDiscoveredBeforeStartingRuntime() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.node.roles=scheduler",
                "--firefly.plugins=missing-plugin"
        }, Map.of());

        assertThrows(IllegalArgumentException.class, () -> FireflyBootstrap.start(options));
    }

    @Test
    void rejectsConfiguredPluginForAnUnsupportedApiLevelBeforePluginStartup() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.node.roles=scheduler",
                "--firefly.plugins=future-api-test"
        }, Map.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> FireflyBootstrap.start(options)
        );

        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("future-api-test"));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("host API level is 1"));
        assertEquals(0, FutureApiClasspathTestPlugin.STARTED.get());
    }
}
