package com.firefly.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StartupTransactionTest {
    @Test
    void rollsBackInReverseOrder() {
        List<String> events = new ArrayList<>();

        try (StartupTransaction startup = new StartupTransaction()) {
            startup.add("first", () -> events.add("first"));
            startup.add("second", () -> events.add("second"));
        }

        assertEquals(List.of("second", "first"), events);
    }

    @Test
    void commitTransfersLifecycleOwnershipToTheBootstrap() {
        List<String> events = new ArrayList<>();

        try (StartupTransaction startup = new StartupTransaction()) {
            startup.add("component", () -> events.add("closed"));
            startup.commit();
        }

        assertEquals(List.of(), events);
    }
}
