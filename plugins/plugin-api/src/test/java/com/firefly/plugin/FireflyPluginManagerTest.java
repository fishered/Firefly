package com.firefly.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void acceptsLegacyPluginThroughTheLevelOneDefault() {
        FireflyPlugin legacy = plugin("legacy", new ArrayList<>());

        try (FireflyPluginManager manager = new FireflyPluginManager(List.of(legacy))) {
            manager.start(FireflyPluginContext.builder().build());
            assertEquals("ACTIVE", manager.descriptors().getFirst().status());
        }
    }

    @Test
    void rejectsIncompatiblePluginBeforeAnyPluginStarts() {
        List<String> events = new ArrayList<>();
        FireflyPlugin compatible = plugin("compatible", events);
        FireflyPlugin incompatible = new FireflyPlugin() {
            @Override
            public String id() {
                return "future-only";
            }

            @Override
            public FireflyPluginCompatibility compatibility() {
                return new FireflyPluginCompatibility(2, 3);
            }

            @Override
            public void start(FireflyPluginContext context) {
                events.add("start:future-only");
            }
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new FireflyPluginManager(
                        List.of(compatible, incompatible), () -> events.add("close:discovery")
                ));

        assertTrue(failure.getMessage().contains("future-only"));
        assertTrue(failure.getMessage().contains("2..3"));
        assertTrue(failure.getMessage().contains("host API level is 1"));
        assertFalse(events.stream().anyMatch(event -> event.startsWith("start:")));
        assertEquals(List.of("close:discovery"), events);
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
