package com.firefly.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FireflyPluginManagerTest {
    @Test
    void startsInOrderAndClosesInReverseOrder() {
        List<String> events = new ArrayList<>();
        FireflyPlugin first = plugin("first", events);
        FireflyPlugin second = plugin("second", events);

        try (FireflyPluginManager manager = new FireflyPluginManager(List.of(first, second))) {
            assertEquals(List.of("LOADED", "LOADED"),
                    manager.descriptors().stream().map(FireflyPluginDescriptor::status).toList());
            manager.start(FireflyPluginContext.builder().build());
            assertEquals(List.of("ACTIVE", "ACTIVE"),
                    manager.descriptors().stream().map(FireflyPluginDescriptor::status).toList());
        }

        assertEquals(List.of("start:first", "start:second", "close:second", "close:first"), events);
    }

    @Test
    void rollsBackStartedPluginsAndDiscoveryWhenStartupFails() {
        List<String> events = new ArrayList<>();
        FireflyPlugin failing = new FireflyPlugin() {
            @Override
            public String id() {
                return "failing";
            }

            @Override
            public void start(FireflyPluginContext context) {
                events.add("start:failing");
                throw new IllegalStateException("failed");
            }
        };
        FireflyPluginManager manager = new FireflyPluginManager(
                List.of(plugin("first", events), failing),
                () -> events.add("close:discovery")
        );

        assertThrows(IllegalStateException.class,
                () -> manager.start(FireflyPluginContext.builder().build()));

        assertEquals(List.of(
                "start:first", "start:failing", "close:first", "close:discovery"
        ), events);
        manager.close();
        assertEquals(4, events.size());
    }

    private FireflyPlugin plugin(String id, List<String> events) {
        return new FireflyPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void start(FireflyPluginContext context) {
                events.add("start:" + id);
            }

            @Override
            public void close() {
                events.add("close:" + id);
            }
        };
    }
}
