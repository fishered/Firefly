package com.firefly.benchmark.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedJavaProcessTest {
    @Test
    void startsASeparateJvmAndCapturesOutput() throws Exception {
        try (ManagedJavaProcess process = ManagedJavaProcess.start(
                "sample", SampleMain.class.getName(), List.of("ready"), Map.of()
        )) {
            assertTrue(awaitOutput(process, "sample:ready", Duration.ofSeconds(5)));
        }
    }

    @Test
    void canForciblyStopASeparateJvm() throws Exception {
        try (ManagedJavaProcess process = ManagedJavaProcess.start(
                "sleeping", SleepingMain.class.getName(), List.of(), Map.of()
        )) {
            assertTrue(awaitOutput(process, "sleeping", Duration.ofSeconds(5)));

            process.destroyForciblyAndWait(Duration.ofSeconds(5));

            assertFalse(process.isAlive());
        }
    }

    private static boolean awaitOutput(ManagedJavaProcess process, String expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (process.output().stream().anyMatch(line -> line.contains(expected))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    public static final class SampleMain {
        private SampleMain() {
        }

        public static void main(String[] args) {
            System.out.println("sample:" + args[0]);
        }
    }

    public static final class SleepingMain {
        private SleepingMain() {
        }

        public static void main(String[] args) throws Exception {
            System.out.println("sleeping");
            Thread.sleep(Duration.ofMinutes(10));
        }
    }
}
