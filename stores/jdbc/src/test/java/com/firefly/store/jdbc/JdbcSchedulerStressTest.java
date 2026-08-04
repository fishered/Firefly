package com.firefly.store.jdbc;

import com.firefly.cluster.ShardLease;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.JobDispatcher;
import com.firefly.engine.SchedulerEngine;
import com.firefly.engine.SchedulerEngineOptions;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stress")
class JdbcSchedulerStressTest {
    private static final int SHARD_COUNT = 32;

    @Test
    void schedulesAndCompletesRemoteOutboxWithoutLossUnderConcurrentLoad() throws Exception {
        StressConfig config = StressConfig.fromSystemProperties();
        PooledDriverManagerDataSource dataSource = dataSource(config);
        JvmResourceSampler resourceSampler = new JvmResourceSampler();
        try {
            resourceSampler.start();
            JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("postgresql"));
            if (config.resetStressState()) {
                resetStressState(dataSource);
            }
            JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
            JdbcExecutionRepository executions = new JdbcExecutionRepository(dataSource);
            String runId = "v104-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String jobPrefix = "stress-" + runId + "-";
            Instant initialNextFireTime = Instant.now().plus(Duration.ofDays(1));

            long suiteStarted = System.nanoTime();
            long registrationStarted = System.nanoTime();
            parallel(config.registrationThreads(), config.jobs(), index -> jobs.save(
                    job(config, jobPrefix, index), initialNextFireTime
            ));
            Duration registrationDuration = elapsed(registrationStarted);

            JdbcShardManager shardManager = new JdbcShardManager(dataSource);
            Map<Integer, ShardLease> leases = acquireAllShards(shardManager, runId);
            Instant scheduledFireTime = Instant.now().plusMillis(config.fireTimeLeadMillis());
            assertEquals(config.jobs(), armJobs(dataSource, jobPrefix, scheduledFireTime));
            await(scheduledFireTime);
            long schedulingStarted = System.nanoTime();
            runSchedulers(config, jobs, leases);
            awaitCount(dataSource, "firefly_execution", jobPrefix, config.jobs(), config.phaseTimeout());
            Duration schedulingDuration = elapsed(schedulingStarted);
            Percentiles schedulingLatency = schedulingLatency(dataSource, jobPrefix);

            assertEquals(config.jobs(), count(dataSource, "firefly_job", jobPrefix));
            assertEquals(config.jobs(), count(dataSource, "firefly_execution", jobPrefix));
            assertEquals(config.jobs(), count(dataSource, "firefly_dispatch_outbox", jobPrefix));
            assertEquals(0, countJobsNotAdvancedBeyond(dataSource, jobPrefix, scheduledFireTime));

            Set<String> claimedOutboxIds = ConcurrentHashMap.newKeySet();
            AtomicInteger duplicateClaims = new AtomicInteger();
            List<Long> completionLatenciesNanos = Collections.synchronizedList(new ArrayList<>());
            long dispatchStarted = System.nanoTime();
            completeOutboxConcurrently(
                    config, jobs, executions, jobPrefix,
                    claimedOutboxIds, duplicateClaims, completionLatenciesNanos
            );
            Duration dispatchDuration = elapsed(dispatchStarted);
            Duration totalDuration = elapsed(suiteStarted);
            ResourceUsage resourceUsage = resourceSampler.snapshot(totalDuration);

            Map<ExecutionStatus, Long> executionStatuses = executionStatusCounts(dataSource, jobPrefix);
            Map<DispatchOutboxStatus, Long> outboxStatuses = outboxStatusCounts(dataSource, jobPrefix);
            assertEquals(config.jobs(), executionStatuses.getOrDefault(ExecutionStatus.SUCCEEDED, 0L));
            assertEquals(config.jobs(), outboxStatuses.getOrDefault(DispatchOutboxStatus.DONE, 0L));
            assertEquals(0, nonTerminalOutboxCount(outboxStatuses));
            assertEquals(0, duplicateClaims.get());
            assertEquals(0, duplicateExecutionIds(dataSource, jobPrefix));
            assertEquals(0, duplicateOutboxIds(dataSource, jobPrefix));

            StressResult result = new StressResult(
                    runId, config, registrationDuration, schedulingDuration, dispatchDuration, totalDuration,
                    claimedOutboxIds.size(), duplicateClaims.get(), executionStatuses, outboxStatuses,
                    schedulingLatency, percentiles(completionLatenciesNanos), resourceUsage
            );
            writeReport(result);
            System.out.println(result.summaryLine());
        } finally {
            resourceSampler.close();
            dataSource.close();
        }
    }

    private JobDefinition job(StressConfig config, String jobPrefix, int index) {
        String id = jobPrefix + index;
        return JobDefinition.builder()
                .id(id)
                .name("Stress job " + index)
                .handlerName("remote:stress-executor:handle")
                .schedule(new FixedRateSchedule(Duration.ofHours(1)))
                .timeout(config.jobTimeout())
                .dispatchMode(ExecutorDispatchMode.UNICAST)
                .completionPolicy(ExecutorCompletionPolicy.ALL_SUCCESS)
                .build();
    }

    private Map<Integer, ShardLease> acquireAllShards(JdbcShardManager shardManager, String runId) {
        Map<Integer, ShardLease> leases = new java.util.LinkedHashMap<>();
        for (int shardId = 0; shardId < SHARD_COUNT; shardId++) {
            ShardLease lease = shardManager.acquire(
                    shardId, "stress-scheduler-" + runId, Instant.now(), Duration.ofMinutes(10)
            ).orElseThrow();
            leases.put(shardId, lease);
        }
        return Map.copyOf(leases);
    }

    private void runSchedulers(
            StressConfig config,
            JdbcJobRepository jobs,
            Map<Integer, ShardLease> leases
    ) throws Exception {
        Clock clock = Clock.systemUTC();
        ExecutorService dispatchPool = Executors.newFixedThreadPool(1);
        try {
            parallel(config.schedulerThreads(), config.schedulerThreads(), schedulerIndex -> {
                Map<Integer, ShardLease> ownedLeases = config.topology() == Topology.PARTITIONED
                        ? leases.entrySet().stream()
                        .filter(entry -> entry.getKey() % config.schedulerThreads() == schedulerIndex)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, Map.Entry::getValue
                        ))
                        : leases;
                JobDispatcher dispatcher = new JobDispatcher(
                        new InMemoryJobHandlerRegistry(), dispatchPool, clock
                );
                SchedulerEngine engine = new SchedulerEngine(
                        jobs, dispatcher, clock, () -> ownedLeases, SHARD_COUNT, true,
                        new com.firefly.metrics.SchedulerMetrics(),
                        new SchedulerEngineOptions(
                                50_000, Duration.ofMillis(500), config.schedulingBatchSize()
                        )
                );
                engine.tick();
            });
        } finally {
            dispatchPool.shutdownNow();
        }
    }

    private int armJobs(DataSource dataSource, String jobPrefix, Instant scheduledFireTime) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_job set next_fire_time = ?, version = version + 1
                     where job_id like ?
                     """)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(scheduledFireTime));
            statement.setString(2, jobPrefix + "%");
            return statement.executeUpdate();
        }
    }

    private void await(Instant target) throws InterruptedException {
        while (Instant.now().isBefore(target)) {
            long remaining = Duration.between(Instant.now(), target).toMillis();
            Thread.sleep(Math.max(1, Math.min(remaining, 25)));
        }
    }

    private void completeOutboxConcurrently(
            StressConfig config,
            JdbcJobRepository jobs,
            JdbcExecutionRepository executions,
            String jobPrefix,
            Set<String> claimedOutboxIds,
            AtomicInteger duplicateClaims,
            List<Long> completionLatenciesNanos
    ) throws Exception {
        AtomicInteger completed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(config.outboxWorkers());
        CountDownLatch ready = new CountDownLatch(config.outboxWorkers());
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Instant deadline = Instant.now().plus(config.phaseTimeout());
        for (int workerIndex = 0; workerIndex < config.outboxWorkers(); workerIndex++) {
            String workerId = "stress-worker-" + workerIndex;
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    while (completed.get() < config.jobs() && Instant.now().isBefore(deadline)) {
                        List<DispatchOutboxRecord> records = jobs.claimDispatches(
                                workerId, Instant.now(), config.claimBatchSize(),
                                Duration.ofSeconds(30), Set.of(DispatchType.REMOTE)
                        );
                        if (records.isEmpty()) {
                            Thread.sleep(5);
                            continue;
                        }
                        for (DispatchOutboxRecord record : records) {
                            long started = System.nanoTime();
                            if (!claimedOutboxIds.add(record.outboxId())) {
                                duplicateClaims.incrementAndGet();
                            }
                            boolean sent = jobs.markClaimedDispatchSentFor(
                                    record.outboxId(), workerId, record.attempt(), Duration.ofSeconds(30)
                            );
                            markExecutionSucceeded(executions, record);
                            boolean acknowledged = jobs.acknowledgeDispatch(
                                    record.command().executionId(), Instant.now()
                            );
                            if (sent && acknowledged) {
                                completed.incrementAndGet();
                                completionLatenciesNanos.add(System.nanoTime() - started);
                            } else {
                                failures.add(new AssertionError("failed to complete " + record.outboxId()));
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(config.phaseTimeout().toSeconds(), TimeUnit.SECONDS));
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("outbox worker failure");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        assertEquals(config.jobs(), completed.get());
    }

    private void markExecutionSucceeded(JdbcExecutionRepository executions, DispatchOutboxRecord record) {
        Instant now = Instant.now();
        var command = record.command();
        var definition = command.definition();
        executions.saveExecution(new ExecutionRecord(
                command.executionId(), command.rootExecutionId(), command.runAttempt(), definition.id(),
                command.scheduledFireTime(), command.dispatchTime(), definition.dispatchMode(),
                definition.completionPolicy(), ExecutionStatus.SUCCEEDED, 1, 1,
                command.ownerNodeId(), command.fencingToken(), now, now
        ));
    }

    private void awaitCount(
            DataSource dataSource,
            String table,
            String jobPrefix,
            int expected,
            Duration timeout
    ) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (count(dataSource, table, jobPrefix) == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expected, count(dataSource, table, jobPrefix));
    }

    private void resetStressState(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, """
                        delete from firefly_execution_target
                        where execution_id in (
                            select execution_id from firefly_execution where job_id like 'stress-v104-%'
                        )
                        """);
                executeUpdate(connection, "delete from firefly_dispatch_outbox where job_id like 'stress-v104-%'");
                executeUpdate(connection, "delete from firefly_execution where job_id like 'stress-v104-%'");
                executeUpdate(connection, "delete from firefly_job where job_id like 'stress-v104-%'");
                executeUpdate(connection, "delete from firefly_shard_lease where owner_node_id like 'stress-scheduler%'");
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void executeUpdate(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private long count(DataSource dataSource, String table, String jobPrefix) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from " + table + " where job_id like ?"
             )) {
            statement.setString(1, jobPrefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private long countJobsNotAdvancedBeyond(DataSource dataSource, String jobPrefix, Instant scheduledFireTime)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*) from firefly_job
                     where job_id like ? and next_fire_time <= ?
                     """)) {
            statement.setString(1, jobPrefix + "%");
            statement.setObject(2, scheduledFireTime.atOffset(ZoneOffset.UTC).toLocalDateTime());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private Percentiles schedulingLatency(DataSource dataSource, String jobPrefix) throws SQLException {
        List<Long> millis = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select scheduled_fire_time, dispatch_time from firefly_execution
                     where job_id like ?
                     """)) {
            statement.setString(1, jobPrefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Instant scheduled = resultSet.getTimestamp(1).toInstant();
                    Instant dispatched = resultSet.getTimestamp(2).toInstant();
                    millis.add(Math.max(0, Duration.between(scheduled, dispatched).toMillis()));
                }
            }
        }
        return millisPercentiles(millis);
    }

    private Map<ExecutionStatus, Long> executionStatusCounts(DataSource dataSource, String jobPrefix) throws SQLException {
        EnumMap<ExecutionStatus, Long> counts = new EnumMap<>(ExecutionStatus.class);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select status, count(*) from firefly_execution
                     where job_id like ?
                     group by status
                     """)) {
            statement.setString(1, jobPrefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(ExecutionStatus.valueOf(resultSet.getString(1)), resultSet.getLong(2));
                }
            }
        }
        return Map.copyOf(counts);
    }

    private Map<DispatchOutboxStatus, Long> outboxStatusCounts(DataSource dataSource, String jobPrefix) throws SQLException {
        EnumMap<DispatchOutboxStatus, Long> counts = new EnumMap<>(DispatchOutboxStatus.class);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select status, count(*) from firefly_dispatch_outbox
                     where job_id like ?
                     group by status
                     """)) {
            statement.setString(1, jobPrefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(DispatchOutboxStatus.valueOf(resultSet.getString(1)), resultSet.getLong(2));
                }
            }
        }
        return Map.copyOf(counts);
    }

    private long nonTerminalOutboxCount(Map<DispatchOutboxStatus, Long> counts) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey() != DispatchOutboxStatus.DONE)
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private long duplicateExecutionIds(DataSource dataSource, String jobPrefix) throws SQLException {
        return duplicateIds(dataSource, "firefly_execution", "execution_id", jobPrefix);
    }

    private long duplicateOutboxIds(DataSource dataSource, String jobPrefix) throws SQLException {
        return duplicateIds(dataSource, "firefly_dispatch_outbox", "outbox_id", jobPrefix);
    }

    private long duplicateIds(DataSource dataSource, String table, String idColumn, String jobPrefix) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*) from (
                         select %s from %s where job_id like ? group by %s having count(*) > 1
                     ) duplicates
                     """.formatted(idColumn, table, idColumn))) {
            statement.setString(1, jobPrefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void parallel(int threads, int itemCount, IntConsumer consumer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cursor = new AtomicInteger();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int worker = 0; worker < threads; worker++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (;;) {
                        int index = cursor.getAndIncrement();
                        if (index >= itemCount) {
                            return;
                        }
                        consumer.accept(index);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(StressConfig.workerTimeout().toSeconds(), TimeUnit.SECONDS));
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("parallel worker failure");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private Percentiles percentiles(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return new Percentiles(0, 0, 0, 0);
        }
        return new Percentiles(
                percentileMillis(sorted, 50),
                percentileMillis(sorted, 95),
                percentileMillis(sorted, 99),
                Duration.ofNanos(sorted.getLast()).toMillis()
        );
    }

    private Percentiles millisPercentiles(List<Long> millis) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        if (sorted.isEmpty()) return new Percentiles(0, 0, 0, 0);
        return new Percentiles(
                percentileValue(sorted, 50), percentileValue(sorted, 95),
                percentileValue(sorted, 99), sorted.getLast()
        );
    }

    private long percentileValue(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private long percentileMillis(List<Long> sortedNanos, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.size()) - 1;
        index = Math.max(0, Math.min(sortedNanos.size() - 1, index));
        return Duration.ofNanos(sortedNanos.get(index)).toMillis();
    }

    private void writeReport(StressResult result) throws Exception {
        Path report = Path.of(System.getProperty(
                "firefly.stress.report.path",
                "build/reports/stress/firefly-v1.0.4-stress-result.json"
        ));
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, result.toJson());
    }

    private PooledDriverManagerDataSource dataSource(StressConfig config) throws SQLException {
        return new PooledDriverManagerDataSource(
                config.jdbcUrl(), config.username(), config.password(), config.maxConnections()
        );
    }

    private record StressConfig(
            String jdbcUrl,
            String username,
            String password,
            int jobs,
            int registrationThreads,
            int schedulerThreads,
            int outboxWorkers,
            int claimBatchSize,
            int maxConnections,
            Topology topology,
            int schedulingBatchSize,
            int fireTimeLeadMillis,
            Duration jobTimeout,
            Duration phaseTimeout,
            boolean resetStressState
    ) {
        static StressConfig fromSystemProperties() {
            return new StressConfig(
                    property("firefly.stress.jdbc.url", property("firefly.jdbc.url",
                            "jdbc:postgresql://127.0.0.1:5432/firefly_stress")),
                    property("firefly.stress.jdbc.username", property("firefly.jdbc.username", "postgres")),
                    property("firefly.stress.jdbc.password", property("firefly.jdbc.password", "123456")),
                    intProperty("firefly.stress.jobs", 5_000),
                    intProperty("firefly.stress.registrationThreads", 8),
                    intProperty("firefly.stress.schedulerThreads", 8),
                    intProperty("firefly.stress.outboxWorkers", 12),
                    intProperty("firefly.stress.claimBatchSize", 100),
                    intProperty("firefly.stress.maxConnections", 32),
                    Topology.parse(property("firefly.stress.topology", "partitioned")),
                    intProperty("firefly.stress.schedulingBatchSize", 200),
                    intProperty("firefly.stress.fireTimeLeadMillis", 1_000),
                    Duration.ofMinutes(intProperty("firefly.stress.jobTimeoutMinutes", 5)),
                    Duration.ofSeconds(intProperty("firefly.stress.phaseTimeoutSeconds", 120)),
                    booleanProperty("firefly.stress.resetState", true)
            );
        }

        private static String property(String name, String defaultValue) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_'));
            }
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int intProperty(String name, int defaultValue) {
            return Integer.parseInt(property(name, Integer.toString(defaultValue)));
        }

        private static boolean booleanProperty(String name, boolean defaultValue) {
            return Boolean.parseBoolean(property(name, Boolean.toString(defaultValue)));
        }

        private static Duration workerTimeout() {
            return Duration.ofSeconds(intProperty("firefly.stress.workerTimeoutSeconds", 600));
        }
    }

    private enum Topology {
        CONTENTION,
        PARTITIONED;

        static Topology parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private record Percentiles(long p50Millis, long p95Millis, long p99Millis, long maxMillis) {
    }

    private record StressResult(
            String runId,
            StressConfig config,
            Duration registrationDuration,
            Duration schedulingDuration,
            Duration dispatchDuration,
            Duration totalDuration,
            int claimedOutboxRecords,
            int duplicateClaims,
            Map<ExecutionStatus, Long> executionStatuses,
            Map<DispatchOutboxStatus, Long> outboxStatuses,
            Percentiles schedulingLatency,
            Percentiles completionLatency,
            ResourceUsage resourceUsage
    ) {
        String summaryLine() {
            return "FIREFLY_STRESS_RESULT runId=" + runId
                    + " jobs=" + config.jobs()
                    + " topology=" + config.topology().name().toLowerCase(Locale.ROOT)
                    + " executions=SUCCEEDED:" + executionStatuses.getOrDefault(ExecutionStatus.SUCCEEDED, 0L)
                    + " outbox=DONE:" + outboxStatuses.getOrDefault(DispatchOutboxStatus.DONE, 0L)
                    + " duplicateClaims=" + duplicateClaims
                    + " totalMs=" + totalDuration.toMillis()
                    + " p95SchedulingMs=" + schedulingLatency.p95Millis()
                    + " p95CompletionMs=" + completionLatency.p95Millis();
        }

        String toJson() {
            return """
                    {
                      "version": "v1.0.4",
                      "runId": "%s",
                      "jdbcUrl": "%s",
                      "jobs": %d,
                      "registrationThreads": %d,
                      "schedulerThreads": %d,
                      "topology": "%s",
                      "schedulingBatchSize": %d,
                      "outboxWorkers": %d,
                      "claimBatchSize": %d,
                      "maxConnections": %d,
                      "jobTimeoutMs": %d,
                      "registrationMs": %d,
                      "schedulingMs": %d,
                      "dispatchCompletionMs": %d,
                      "totalMs": %d,
                      "claimedOutboxRecords": %d,
                      "duplicateClaims": %d,
                      "executionStatuses": %s,
                      "outboxStatuses": %s,
                      "schedulingLatencyMs": {
                        "p50": %d,
                        "p95": %d,
                        "p99": %d,
                        "max": %d
                      },
                      "completionLatencyMs": {
                        "p50": %d,
                        "p95": %d,
                        "p99": %d,
                        "max": %d
                      },
                      "jvmResources": {
                        "availableProcessors": %d,
                        "peakProcessCpuPercent": %.2f,
                        "averageProcessCpuPercent": %.2f,
                        "peakHeapUsedBytes": %d,
                        "peakNonHeapUsedBytes": %d
                      }
                    }
                    """.formatted(
                    runId,
                    config.jdbcUrl().replace("\\", "\\\\").replace("\"", "\\\""),
                    config.jobs(), config.registrationThreads(), config.schedulerThreads(),
                    config.topology().name().toLowerCase(Locale.ROOT), config.schedulingBatchSize(),
                    config.outboxWorkers(), config.claimBatchSize(), config.maxConnections(),
                    config.jobTimeout().toMillis(),
                    registrationDuration.toMillis(), schedulingDuration.toMillis(),
                    dispatchDuration.toMillis(), totalDuration.toMillis(),
                    claimedOutboxRecords, duplicateClaims,
                    enumMapJson(executionStatuses), enumMapJson(outboxStatuses),
                    schedulingLatency.p50Millis(), schedulingLatency.p95Millis(),
                    schedulingLatency.p99Millis(), schedulingLatency.maxMillis(),
                    completionLatency.p50Millis(), completionLatency.p95Millis(),
                    completionLatency.p99Millis(), completionLatency.maxMillis(),
                    resourceUsage.availableProcessors(), resourceUsage.peakProcessCpuPercent(),
                    resourceUsage.averageProcessCpuPercent(), resourceUsage.peakHeapUsedBytes(),
                    resourceUsage.peakNonHeapUsedBytes()
            );
        }

        private String enumMapJson(Map<? extends Enum<?>, Long> values) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (var entry : values.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append('"').append(entry.getKey().name()).append("\": ").append(entry.getValue());
            }
            builder.append('}');
            return builder.toString();
        }
    }

    private record ResourceUsage(
            int availableProcessors,
            double peakProcessCpuPercent,
            double averageProcessCpuPercent,
            long peakHeapUsedBytes,
            long peakNonHeapUsedBytes
    ) {
    }

    private static final class JvmResourceSampler implements AutoCloseable {
        private final com.sun.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        private final java.lang.management.MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stress-resource-sampler");
            thread.setDaemon(true);
            return thread;
        });
        private final DoubleAccumulator peakCpuLoad = new DoubleAccumulator(Math::max, 0.0);
        private final AtomicLong peakHeapUsed = new AtomicLong();
        private final AtomicLong peakNonHeapUsed = new AtomicLong();
        private long initialCpuTime;

        void start() {
            initialCpuTime = operatingSystem.getProcessCpuTime();
            sampler.scheduleAtFixedRate(this::sample, 0, 100, TimeUnit.MILLISECONDS);
        }

        private void sample() {
            double cpuLoad = operatingSystem.getProcessCpuLoad();
            if (cpuLoad >= 0) peakCpuLoad.accumulate(cpuLoad * 100.0);
            peakHeapUsed.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
            peakNonHeapUsed.accumulateAndGet(memory.getNonHeapMemoryUsage().getUsed(), Math::max);
        }

        ResourceUsage snapshot(Duration wallTime) {
            sample();
            int processors = operatingSystem.getAvailableProcessors();
            long usedCpuTime = Math.max(0, operatingSystem.getProcessCpuTime() - initialCpuTime);
            double averageCpu = wallTime.isZero() ? 0.0
                    : usedCpuTime * 100.0 / wallTime.toNanos() / processors;
            return new ResourceUsage(
                    processors, peakCpuLoad.get(), averageCpu,
                    peakHeapUsed.get(), peakNonHeapUsed.get()
            );
        }

        @Override
        public void close() {
            sampler.shutdownNow();
        }
    }

    private static final class PooledDriverManagerDataSource implements DataSource, AutoCloseable {
        private final String username;
        private final String password;
        private final BlockingQueue<Connection> available;
        private final List<Connection> allConnections = new ArrayList<>();

        private PooledDriverManagerDataSource(String url, String username, String password, int maxConnections)
                throws SQLException {
            this.username = username;
            this.password = password;
            this.available = new ArrayBlockingQueue<>(maxConnections);
            for (int index = 0; index < maxConnections; index++) {
                Connection connection = DriverManager.getConnection(url, username, password);
                allConnections.add(connection);
                available.add(connection);
            }
        }

        @Override public Connection getConnection() throws SQLException {
            try {
                Connection connection = available.poll(30, TimeUnit.SECONDS);
                if (connection == null) {
                    throw new SQLException("timed out waiting for pooled PostgreSQL connection");
                }
                return pooledConnection(connection);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while waiting for pooled PostgreSQL connection", e);
            }
        }

        @Override public Connection getConnection(String user, String pass) throws SQLException {
            if (!username.equals(user) || !password.equals(pass)) {
                throw new SQLException("pooled stress DataSource does not support alternate credentials");
            }
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() {
            return null;
        }

        @Override public void setLogWriter(PrintWriter out) {
        }

        @Override public void setLoginTimeout(int seconds) {
        }

        @Override public int getLoginTimeout() {
            return 0;
        }

        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not wrapped");
        }

        @Override public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (Connection connection : allConnections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private Connection pooledConnection(Connection connection) {
            AtomicBoolean returned = new AtomicBoolean();
            InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
                if (method.getName().equals("close") && method.getParameterCount() == 0) {
                    if (returned.compareAndSet(false, true)) {
                        if (!connection.getAutoCommit()) {
                            connection.rollback();
                            connection.setAutoCommit(true);
                        }
                        connection.clearWarnings();
                        available.offer(connection);
                    }
                    return null;
                }
                if (method.getName().equals("isClosed") && method.getParameterCount() == 0 && returned.get()) {
                    return true;
                }
                if (method.getName().equals("unwrap") && args != null && args.length == 1
                        && args[0] instanceof Class<?> type && type.isInstance(connection)) {
                    return type.cast(connection);
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, handler
            );
        }
    }
}
