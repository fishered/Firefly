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

    @Test
    void rejectsLegacyBinaryWhenHostPluginApiMovesForward() {
        FireflyPlugin legacy = plugin("legacy", new ArrayList<>());
        FireflyHostCompatibility futureHost = new FireflyHostCompatibility(
                "1.1.0", 2, 1, 2, 12, 12
        );
        assertThrows(IllegalArgumentException.class, () -> new FireflyPluginManager(
                List.of(legacy), () -> { }, futureHost
        ));
    }

    @Test
    void validatesRollingUpgradeIntersectionAcrossProtocolAndDatabase() {
        FireflyPluginRuntimeCompatibility tracing = new FireflyPluginRuntimeCompatibility(
                new FireflyPluginCompatibility(1, 1), "1.0.0", "1.0.6", 1, 2, 12, 12, true
        );
        FireflyCompatibilityMatrix.requireValid(List.of(
                new FireflyHostCompatibility("1.0.4", 1, 1, 2, 12, 12),
                new FireflyHostCompatibility("1.0.6", 1, 1, 2, 12, 12)
        ), List.of(tracing));
        assertThrows(IllegalArgumentException.class, () -> FireflyCompatibilityMatrix.requireValid(List.of(
                new FireflyHostCompatibility("1.0.6", 1, 2, 2, 12, 12),
                new FireflyHostCompatibility("1.0.4", 1, 1, 1, 12, 12)
        ), List.of(tracing)));
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
