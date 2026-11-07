package com.firefly.server;

import com.firefly.governance.TenantAdmissionReason;
import com.firefly.governance.TenantCapacityObservationBuffer;
import com.firefly.governance.TenantCapacityStore;
import com.firefly.governance.TenantQuotaStore;
import com.firefly.metrics.SchedulerMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Asynchronous bounded capacity sampling, flush, and retention worker. */
public final class TenantCapacitySampler implements AutoCloseable {
    private final TenantQuotaStore quotas;
    private final TenantCapacityStore capacity;
    private final TenantCapacityObservationBuffer buffer;
    private final SchedulerMetrics metrics;
    private final Clock clock;
    private final BooleanSupplier authoritativeOwner;
    private final Duration interval;
    private final Duration retention;
    private final int cleanupBatch;
    private final Map<SchedulerMetrics.TenantAdmissionMetricKey, Long> previousRejections = new HashMap<>();
    private ScheduledExecutorService executor;

    public TenantCapacitySampler(
            TenantQuotaStore quotas,
            TenantCapacityStore capacity,
            TenantCapacityObservationBuffer buffer,
            SchedulerMetrics metrics,
            Clock clock,
            BooleanSupplier authoritativeOwner,
            Duration interval,
            Duration retention,
            int cleanupBatch
    ) {
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authoritativeOwner = Objects.requireNonNull(authoritativeOwner, "authoritativeOwner");
        this.interval = requirePositive(interval, "interval");
        this.retention = requirePositive(retention, "retention");
        if (cleanupBatch < 1 || cleanupBatch > 10_000) {
            throw new IllegalArgumentException("cleanupBatch must be between 1 and 10000");
        }
        this.cleanupBatch = cleanupBatch;
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "firefly-tenant-capacity-sampler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void sampleNow() {
        Instant now = clock.instant();
        captureRejections(now);
        if (authoritativeOwner.getAsBoolean()) {
            quotas.list().forEach(policy -> buffer.observeSnapshot(policy, quotas.usage(policy.tenantId(), now), now));
        }
        buffer.flushClosed(now);
        metrics.tenantCapacityFlushLagSeconds(buffer.pendingBuckets() == 0 ? 0 : interval.toSeconds());
        if (authoritativeOwner.getAsBoolean()) capacity.deleteBefore(now.minus(retention), cleanupBatch);
    }

    private void captureRejections(Instant now) {
        Map<SchedulerMetrics.TenantAdmissionMetricKey, Long> current = metrics.tenantAdmissionRejections();
        current.forEach((key, value) -> {
            long previous = previousRejections.getOrDefault(key, 0L);
            if (value > previous) buffer.observeRejections(key.tenantId(), key.reason(), value - previous, now);
        });
        previousRejections.clear();
        previousRejections.putAll(current);
    }

    private void runSafely() {
        try {
            sampleNow();
        } catch (RuntimeException failure) {
            metrics.recordTenantCapacityFlushFailure("sampler");
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        buffer.close();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
