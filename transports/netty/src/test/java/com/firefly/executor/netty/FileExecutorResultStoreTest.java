package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExecutorResultStoreTest {
    @Test
    void survivesStoreRecreationAndDeletesExpiredResults(@TempDir Path directory) throws Exception {
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        FileExecutorResultStore first = new FileExecutorResultStore(
                directory, Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC)
        );
        first.save("execution-1", new ExecutorExecutionResult("SUCCEEDED", ""));

        FileExecutorResultStore restarted = new FileExecutorResultStore(
                directory, Duration.ofHours(1), Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC)
        );
        assertEquals("SUCCEEDED", restarted.find("execution-1").orElseThrow().status());

        FileExecutorResultStore expired = new FileExecutorResultStore(
                directory, Duration.ofHours(1), Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC)
        );
        assertTrue(expired.find("execution-1").isEmpty());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.toString().endsWith(".result")).count());
        }
    }

    @Test
    void deletesCorruptResultsInsteadOfRetryingTheReadForever(@TempDir Path directory) throws Exception {
        FileExecutorResultStore store = new FileExecutorResultStore(directory, Duration.ofHours(1));
        store.save("execution-1", new ExecutorExecutionResult("SUCCEEDED", ""));
        Path resultFile;
        try (var files = Files.list(directory)) {
            resultFile = files.filter(path -> path.toString().endsWith(".result")).findFirst().orElseThrow();
        }
        Files.write(resultFile, new byte[]{0, 0, 0, 1, 0});

        assertTrue(store.find("execution-1").isEmpty());
        assertTrue(Files.notExists(resultFile));
    }
}
