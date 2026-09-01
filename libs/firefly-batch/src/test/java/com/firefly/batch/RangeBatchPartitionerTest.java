package com.firefly.batch;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RangeBatchPartitionerTest {
    @Test void partitionsAreGapFreeAndDeterministic() {
        var request = new BatchPartitioner.BatchPartitionRequest("root", 3, 10, "orders", Map.of());
        var parts = new RangeBatchPartitioner().partition(request);
        assertEquals(3, parts.size());
        assertEquals(0, parts.get(0).offset());
        assertEquals(4, parts.get(0).limit());
        assertEquals(4, parts.get(1).offset());
        assertEquals(3, parts.get(1).limit());
        assertEquals(7, parts.get(2).offset());
        assertEquals(3, parts.get(2).limit());
    }
}
