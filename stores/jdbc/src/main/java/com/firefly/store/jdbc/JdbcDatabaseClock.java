package com.firefly.store.jdbc;

import com.firefly.metrics.SchedulerMetrics;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A low-cost clock continuously calibrated against the shared database. */
public final class JdbcDatabaseClock extends Clock implements AutoCloseable {
    private static final Logger log = Logger.getLogger(JdbcDatabaseClock.class.getName());

    private final DataSource dataSource;
    private final Clock baseClock;
    private final JdbcClockOptions options;
    private final SchedulerMetrics metrics;
    private final AtomicLong offsetMillis = new AtomicLong();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-database-clock");
        thread.setDaemon(false);
        return thread;
    });

    public JdbcDatabaseClock(DataSource dataSource, JdbcClockOptions options, SchedulerMetrics metrics) {
        this(dataSource, Clock.systemUTC(), options, metrics);
    }

    JdbcDatabaseClock(
            DataSource dataSource, Clock baseClock, JdbcClockOptions options, SchedulerMetrics metrics
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.baseClock = Objects.requireNonNull(baseClock, "baseClock");
        this.options = Objects.requireNonNull(options, "options");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        refresh(true);
        timer.scheduleWithFixedDelay(
                () -> refresh(false),
                options.syncInterval().toMillis(),
                options.syncInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public ZoneId getZone() {
        return baseClock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        JdbcDatabaseClock source = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId nextZone) {
                return source.withZone(nextZone);
            }

            @Override
            public Instant instant() {
                return source.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return baseClock.instant().plusMillis(offsetMillis.get());
    }

    void refreshNow() {
        refresh(true);
    }

    private void refresh(boolean failFast) {
        Instant before = baseClock.instant();
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = JdbcTimeSource.database().now(connection);
            Instant after = baseClock.instant();
            Duration roundTrip = Duration.between(before, after);
            Instant midpoint = before.plusNanos(Math.max(0, roundTrip.toNanos() / 2));
            long offset = Duration.between(midpoint, databaseNow).toMillis();
            offsetMillis.set(offset);
            metrics.clockOffsetMillis(offset);
            if (Math.abs(offset) > options.driftWarningThreshold().toMillis()) {
                metrics.recordClockDriftWarning();
                log.warning("database clock drift exceeds threshold: offsetMillis=" + offset);
            }
        } catch (SQLException | RuntimeException e) {
            metrics.recordClockSyncFailure();
            if (failFast) throw new JdbcException("failed to calibrate database clock", e);
            log.log(Level.WARNING, "failed to refresh database clock; keeping previous offset", e);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
