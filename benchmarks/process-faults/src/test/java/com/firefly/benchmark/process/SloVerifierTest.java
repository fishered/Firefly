package com.firefly.benchmark.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class SloVerifierTest {
    @Test
    void rejectsMeasurementsOverTheConfiguredLimit() {
        BenchmarkReport report = new BenchmarkReport("slo-test");

        assertThrows(SloViolationException.class, () -> SloVerifier.requireAtMost(
                "failover", Duration.ofSeconds(16), Duration.ofSeconds(15), report
        ));
    }
}
