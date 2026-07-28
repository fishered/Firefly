package com.firefly.benchmark.process;

import java.time.Duration;

public final class SloVerifier {
    private SloVerifier() {
    }

    public static void requireAtMost(String name, Duration actual, Duration limit, BenchmarkReport report) {
        boolean passed = actual.compareTo(limit) <= 0;
        report.add(name, actual.toMillis(), "ms", limit.toMillis(), passed);
        if (!passed) {
            throw new SloViolationException(
                    name + " exceeded SLO: actual=" + actual.toMillis() + "ms, limit=" + limit.toMillis() + "ms"
            );
        }
    }
}
