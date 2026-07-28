package com.firefly.benchmark.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class BenchmarkReportTest {
    @Test
    void writesScenarioMeasurements(@TempDir Path directory) throws Exception {
        BenchmarkReport report = new BenchmarkReport("scheduler-delay");
        report.add("p99", 420, "ms", 500, true);

        Path output = report.write(directory);
        String json = Files.readString(output);

        assertTrue(json.contains("\"scenario\": \"scheduler-delay\""));
        assertTrue(json.contains("\"name\": \"p99\""));
        assertTrue(json.contains("\"passed\": true"));
    }
}
