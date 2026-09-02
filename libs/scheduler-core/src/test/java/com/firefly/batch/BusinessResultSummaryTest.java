package com.firefly.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessResultSummaryTest {
    @Test
    void validatesCountsLocationAndChecksumProtocol() {
        BusinessResultSummary result = new BusinessResultSummary("orders-2026-09-02", 100, 98, 2,
                "cp-1024", "s3://bucket/orders", "sha256:abc");
        assertTrue(result.complete());
        assertThrows(IllegalArgumentException.class, () -> new BusinessResultSummary(
                "orders", 100, 99, 0, "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new BusinessResultSummary(
                "orders", 1, 1, 0, "", "file", "md5:abc"));
    }

    @Test
    void incompleteBusinessResultIsNotComplete() {
        assertFalse(new BusinessResultSummary("orders", 10, 8, 0, "", "file", "").complete());
    }
}
