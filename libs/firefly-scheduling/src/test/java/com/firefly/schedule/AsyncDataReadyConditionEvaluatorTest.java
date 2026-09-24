package com.firefly.schedule;

import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncDataReadyConditionEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void slowConditionDoesNotBlockSchedulerAndEventuallyPublishesItsResult() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        DataReadyCondition condition = condition("manifest", () -> {
            started.countDown();
            try {
                release.await();
                return ConditionStatus.ALLOWED;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ConditionStatus.BLOCKED;
            }
        });
        try (AsyncDataReadyConditionEvaluator evaluator = new AsyncDataReadyConditionEvaluator(
                List.of(condition), clock, Duration.ofSeconds(5), 1)) {
            long before = System.nanoTime();
            assertEquals(ConditionStatus.WAITING, evaluator.evaluate(job(), NOW));
            assertTrue(Duration.ofNanos(System.nanoTime() - before).compareTo(Duration.ofMillis(250)) < 0);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(ConditionStatus.ALLOWED, awaitResult(evaluator));
            assertEquals(0, evaluator.pendingEvaluations());
        }
    }

    @Test
    void timeoutAndFailureFailClosed() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        CountDownLatch blocker = new CountDownLatch(1);
        try (AsyncDataReadyConditionEvaluator timedOut = new AsyncDataReadyConditionEvaluator(
                List.of(condition("manifest", () -> {
                    try {
                        blocker.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return ConditionStatus.ALLOWED;
                })), clock, Duration.ofSeconds(1), 1)) {
            assertEquals(ConditionStatus.WAITING, timedOut.evaluate(job(), NOW));
            clock.advance(Duration.ofSeconds(2));
            assertEquals(ConditionStatus.BLOCKED, timedOut.evaluate(job(), NOW));
        } finally {
            blocker.countDown();
        }

        try (AsyncDataReadyConditionEvaluator failed = new AsyncDataReadyConditionEvaluator(
                List.of(condition("manifest", () -> { throw new IllegalStateException("unavailable"); })),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), 1)) {
            assertEquals(ConditionStatus.WAITING, failed.evaluate(job(), NOW));
            assertEquals(ConditionStatus.BLOCKED, awaitResult(failed));
        }
    }

    private static ConditionStatus awaitResult(AsyncDataReadyConditionEvaluator evaluator) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ConditionStatus result = evaluator.evaluate(job(), NOW);
            if (result != ConditionStatus.WAITING) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("condition evaluation did not complete");
    }

    private static DataReadyCondition condition(String id, java.util.function.Supplier<ConditionStatus> result) {
        return new DataReadyCondition() {
            @Override public String id() { return id; }
            @Override public ConditionStatus evaluate(JobDefinition definition, Instant businessTime) {
                return result.get();
            }
        };
    }

    private static JobDefinition job() {
        return JobDefinition.builder()
                .id("job")
                .name("job")
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .parameters(Map.of(DataReadyConditionEvaluator.CONDITION_IDS_PARAMETER, "manifest"))
                .build();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
