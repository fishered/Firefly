package com.firefly.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionIdsTest {
    @Test
    void boundsLongIdsWithAStableCollisionResistantSuffix() {
        String prefix = "x".repeat(300);
        String first = ExecutionIds.child(prefix, "first");
        String repeated = ExecutionIds.child(prefix, "first");
        String second = ExecutionIds.child(prefix, "second");
        assertEquals(ExecutionIds.MAX_LENGTH, first.length());
        assertEquals(first, repeated);
        assertNotEquals(first, second);
        assertTrue(first.contains("~"));
    }
}
