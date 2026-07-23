package com.firefly.plugin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FireflyPluginConfigurationTest {
    @Test
    void resolvesPluginPropertiesWithFileTakingPrecedenceOverEnvironment() {
        FireflyPluginConfiguration configuration = new FireflyPluginConfiguration(
                Map.of("firefly.plugin.alerts.endpoint", "http://configured"),
                Map.of(
                        "FIREFLY_PLUGIN_ALERTS_ENDPOINT", "http://environment",
                        "FIREFLY_PLUGIN_ALERTS_ENABLED", "true"
                )
        );

        assertEquals("http://configured", configuration.pluginProperty("alerts", "endpoint").orElseThrow());
        assertTrue(configuration.pluginBoolean("alerts", "enabled", false));
    }
}
