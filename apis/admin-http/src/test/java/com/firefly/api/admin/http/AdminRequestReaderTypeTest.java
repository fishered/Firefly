package com.firefly.api.admin.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AdminRequestReaderTypeTest {
    private final AdminRequestReader reader = new AdminRequestReader(AdminRequestLimits.defaults());

    @Test
    void preservesIdsContainingCommas() {
        assertEquals(java.util.List.of("tenant,blue", "tenant-red"),
                reader.ids(Map.of("ids", "[\"tenant,blue\",\"tenant-red\"]"), "ids"));
    }

    @Test
    void rejectsTypoBooleanInsteadOfSilentlyDisabling() {
        assertThrows(IllegalArgumentException.class,
                () -> reader.booleanValue(Map.of("enabled", "treu"), "enabled", true));
    }
}
