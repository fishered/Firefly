package com.firefly.benchmark.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BenchmarkReport {
    private final String scenario;
    private final Instant startedAt;
    private final List<BenchmarkMeasurement> measurements = new ArrayList<>();
    private Instant finishedAt;

    public BenchmarkReport(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        this.scenario = scenario;
        this.startedAt = Instant.now();
    }

    public void add(String name, long value, String unit, long sloLimit, boolean passed) {
        measurements.add(new BenchmarkMeasurement(name, value, unit, sloLimit, passed));
    }

    public void finish() {
        finishedAt = Instant.now();
    }

    public Path write(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (finishedAt == null) {
            finish();
        }
        Files.createDirectories(directory);
        Path output = directory.resolve(scenario + ".json");
        Files.writeString(output, json(), StandardCharsets.UTF_8);
        return output;
    }

    String json() {
        if (finishedAt == null) {
            finish();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        stringField(builder, "scenario", scenario, true);
        stringField(builder, "startedAt", startedAt.toString(), true);
        stringField(builder, "finishedAt", finishedAt.toString(), true);
        builder.append("  \"measurements\": [\n");
        for (int index = 0; index < measurements.size(); index++) {
            BenchmarkMeasurement measurement = measurements.get(index);
            builder.append("    {");
            inlineString(builder, "name", measurement.name());
            builder.append(", ");
            inlineNumber(builder, "value", measurement.value());
            builder.append(", ");
            inlineString(builder, "unit", measurement.unit());
            builder.append(", ");
            inlineNumber(builder, "sloLimit", measurement.sloLimit());
            builder.append(", ");
            inlineBoolean(builder, "passed", measurement.passed());
            builder.append("}");
            if (index + 1 < measurements.size()) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("  ]\n");
        builder.append("}\n");
        return builder.toString();
    }

    private static void stringField(StringBuilder builder, String name, String value, boolean comma) {
        builder.append("  \"").append(escape(name)).append("\": \"")
                .append(escape(value)).append("\"");
        if (comma) {
            builder.append(",");
        }
        builder.append("\n");
    }

    private static void inlineString(StringBuilder builder, String name, String value) {
        builder.append("\"").append(escape(name)).append("\": \"")
                .append(escape(value)).append("\"");
    }

    private static void inlineNumber(StringBuilder builder, String name, long value) {
        builder.append("\"").append(escape(name)).append("\": ").append(value);
    }

    private static void inlineBoolean(StringBuilder builder, String name, boolean value) {
        builder.append("\"").append(escape(name)).append("\": ").append(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
