package io.github.nishi.firefly.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardHasherTest {
    @Test
    void mapsKeyToStableShardRange() {
        int first = ShardHasher.shardFor("billing-job", 32);
        int second = ShardHasher.shardFor("billing-job", 32);

        assertEquals(first, second);
        assertTrue(first >= 0 && first < 32);
    }
}
