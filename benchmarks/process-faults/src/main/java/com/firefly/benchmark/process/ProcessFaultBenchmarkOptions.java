package com.firefly.benchmark.process;

import java.nio.file.Path;
import java.time.Duration;

public record ProcessFaultBenchmarkOptions(
        int taskCount,
        Duration schedulerDelayP99Slo,
        Duration failoverSlo,
        Duration healthTimeout,
        Path reportDirectory
) {
    public ProcessFaultBenchmarkOptions {
        if (taskCount <= 0) {
            throw new IllegalArgumentException("taskCount must be positive");
        }
        if (schedulerDelayP99Slo.isNegative() || schedulerDelayP99Slo.isZero()) {
            throw new IllegalArgumentException("schedulerDelayP99Slo must be positive");
        }
        if (failoverSlo.isNegative() || failoverSlo.isZero()) {
            throw new IllegalArgumentException("failoverSlo must be positive");
        }
        if (healthTimeout.isNegative() || healthTimeout.isZero()) {
            throw new IllegalArgumentException("healthTimeout must be positive");
        }
        if (reportDirectory == null) {
            throw new IllegalArgumentException("reportDirectory must not be null");
        }
    }

    public static ProcessFaultBenchmarkOptions fromSystemProperties() {
        return new ProcessFaultBenchmarkOptions(
                integer("firefly.benchmark.task.count", 10_000),
                duration("firefly.benchmark.slo.scheduler-delay-p99", Duration.ofMillis(500)),
                duration("firefly.benchmark.slo.failover", Duration.ofSeconds(15)),
                duration("firefly.benchmark.health-timeout", Duration.ofSeconds(20)),
                Path.of(System.getProperty(
                        "firefly.benchmark.report.dir",
                        Path.of("build", "reports", "process-faults").toString()
                ))
        );
    }

    private static int integer(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static Duration duration(String name, Duration fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Duration.parse(value);
    }
}
