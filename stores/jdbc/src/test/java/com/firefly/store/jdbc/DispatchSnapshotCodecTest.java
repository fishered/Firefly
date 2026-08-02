package com.firefly.store.jdbc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DispatchSnapshotCodecTest {
    @Test
    void writesVersionedJsonAndReadsLegacyPayloads() {
        Map<String, String> fields = Map.of("id", "job-1", "enabled", "true");
        String encoded = DispatchSnapshotCodec.encode(fields);
        assertTrue(encoded.contains("\"schemaVersion\":1"));
        assertEquals(fields, DispatchSnapshotCodec.decode(encoded));
        assertEquals(fields, DispatchSnapshotCodec.decode(JdbcEncoding.encodeMap(fields)));
    }
}
