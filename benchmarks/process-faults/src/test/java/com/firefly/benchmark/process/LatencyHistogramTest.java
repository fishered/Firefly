package com.firefly.benchmark.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LatencyHistogramTest {
    @Test
    void calculatesNearestRankPercentiles() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int value = 1; value <= 100; value++) {
            histogram.recordMillis(value);
        }

        assertEquals(100, histogram.count());
        assertEquals(99, histogram.percentileMillis(99));
        assertEquals(100, histogram.maxMillis());
    }

    @Test
    void rejectsNegativeLatency() {
        LatencyHistogram histogram = new LatencyHistogram();

        assertThrows(IllegalArgumentException.class, () -> histogram.recordMillis(-1));
    }
}
