package com.firefly.benchmark.process;

public record BenchmarkMeasurement(
        String name,
        long value,
        String unit,
        long sloLimit,
        boolean passed
) {
    public BenchmarkMeasurement {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit must not be blank");
        }
    }
}
