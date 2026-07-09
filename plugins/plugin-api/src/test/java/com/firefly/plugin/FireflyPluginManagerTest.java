package com.firefly.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FireflyPluginManagerTest {
    @Test
    void startsInOrderAndClosesInReverseOrder() {
        List<String> events = new ArrayList<>();
        FireflyPlugin first = plugin("first", events);
        FireflyPlugin second = plugin("second", events);

        try (FireflyPluginManager manager = new FireflyPluginManager(List.of(first, second))) {
            manager.start(FireflyPluginContext.builder().build());
        }

        assertEquals(List.of("start:first", "start:second", "close:second", "close:first"), events);
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
