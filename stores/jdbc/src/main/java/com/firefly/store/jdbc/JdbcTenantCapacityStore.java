package com.firefly.store.jdbc;

import com.firefly.governance.TenantCapacityBaseline;
import com.firefly.governance.TenantCapacityFlush;
import com.firefly.governance.TenantCapacityResource;
import com.firefly.governance.TenantCapacitySample;
import com.firefly.governance.TenantCapacityStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** JDBC capacity store with transactional flush dedupe and bounded aggregation queries. */
public final class JdbcTenantCapacityStore implements TenantCapacityStore {
    private final DataSource dataSource;

    public JdbcTenantCapacityStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean merge(TenantCapacityFlush flush, Instant now) {
        Objects.requireNonNull(flush, "flush");
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!insertFlush(connection, flush, now)) {
                    connection.rollback();
                    return false;
                }
                TenantCapacitySample current = selectSample(connection, flush.sample().tenantId(),
                        flush.sample().bucketStart(), true);
                if (current == null) insertSample(connection, flush.sample(), now);
                else updateSample(connection, mergeSamples(current, flush.sample()), now);
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to merge tenant capacity sample", failure);
        }
    }

    @Override
    public TenantCapacityBaseline baseline(String tenantId, Instant from, Instant to, Duration bucketWidth) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        int expected = expectedBuckets(from, to, bucketWidth);
        String sql = """
                select count(*) as observed_buckets,
                       coalesce(sum(sample_count), 0) as samples,
                       coalesce(min(min_policy_version), 0) as min_policy,
                       coalesce(max(max_policy_version), 0) as max_policy,
                       coalesce(min(min_runtime_version), '') as min_runtime,
                       coalesce(max(max_runtime_version), '') as max_runtime,
                       coalesce(avg(max_active_executions), 0) as avg_concurrency,
                       coalesce(max(max_active_executions), 0) as peak_concurrency,
                       coalesce(sum(saturated_concurrency_samples), 0) as sat_concurrency,
                       coalesce(sum(rejected_concurrency), 0) as rejected_concurrency,
                       coalesce(avg(max_reserved_cpu_millis), 0) as avg_cpu,
                       coalesce(max(max_reserved_cpu_millis), 0) as peak_cpu,
                       coalesce(sum(saturated_cpu_samples), 0) as sat_cpu,
                       coalesce(sum(rejected_cpu), 0) as rejected_cpu,
                       coalesce(avg(max_reserved_memory_bytes), 0) as avg_memory,
                       coalesce(max(max_reserved_memory_bytes), 0) as peak_memory,
                       coalesce(sum(saturated_memory_samples), 0) as sat_memory,
                       coalesce(sum(rejected_memory), 0) as rejected_memory,
                       coalesce(avg(max_backlog), 0) as avg_backlog,
                       coalesce(max(max_backlog), 0) as peak_backlog,
                       coalesce(sum(saturated_backlog_samples), 0) as sat_backlog,
                       coalesce(sum(rejected_backlog), 0) as rejected_backlog,
                       coalesce(avg(max_trigger_rate), 0) as avg_trigger,
                       coalesce(max(max_trigger_rate), 0) as peak_trigger,
                       coalesce(sum(saturated_trigger_rate_samples), 0) as sat_trigger,
                       coalesce(sum(rejected_trigger_rate), 0) as rejected_trigger
                from firefly_tenant_capacity_sample
                where tenant_id=? and bucket_start>=? and bucket_start<? and sample_count>0
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setTimestamp(2, Timestamp.from(from));
            statement.setTimestamp(3, Timestamp.from(to));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt("observed_buckets") == 0) {
                    return TenantCapacityBaseline.empty(tenantId, from, to, expected);
                }
                int observed = result.getInt("observed_buckets");
                long samples = result.getLong("samples");
                List<TenantCapacityBaseline.ResourceBaseline> resources = List.of(
                        resource(result, TenantCapacityResource.CONCURRENCY, "concurrency", samples),
                        resource(result, TenantCapacityResource.CPU_MILLIS, "cpu", samples),
                        resource(result, TenantCapacityResource.MEMORY_BYTES, "memory", samples),
                        resource(result, TenantCapacityResource.BACKLOG, "backlog", samples),
                        resource(result, TenantCapacityResource.TRIGGER_RATE, "trigger", samples)
                );
                double coverage = expected == 0 ? 0 : Math.min(1, observed / (double) expected);
                String minRuntime = result.getString("min_runtime");
                String maxRuntime = result.getString("max_runtime");
                return new TenantCapacityBaseline(tenantId, from, to, observed, expected, coverage,
                        result.getLong("min_policy"), result.getLong("max_policy"),
                        !Objects.equals(minRuntime, maxRuntime), resources);
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to aggregate tenant capacity baseline", failure);
        }
    }

    @Override
    public List<TenantCapacityBaseline> baselines(
            List<String> tenantIds, Instant from, Instant to, Duration bucketWidth
    ) {
        Objects.requireNonNull(tenantIds, "tenantIds");
        int expected = expectedBuckets(from, to, bucketWidth);
        Map<String, TenantCapacityBaseline> resultByTenant = new LinkedHashMap<>();
        tenantIds.forEach(id -> resultByTenant.put(id,
                TenantCapacityBaseline.empty(id, from, to, expected)));
        String sql = """
                select tenant_id, count(*) as observed_buckets,
                       coalesce(sum(sample_count), 0) as samples,
                       coalesce(min(min_policy_version), 0) as min_policy,
                       coalesce(max(max_policy_version), 0) as max_policy,
                       coalesce(min(min_runtime_version), '') as min_runtime,
                       coalesce(max(max_runtime_version), '') as max_runtime,
                       coalesce(avg(max_active_executions), 0) as avg_concurrency,
                       coalesce(max(max_active_executions), 0) as peak_concurrency,
                       coalesce(sum(saturated_concurrency_samples), 0) as sat_concurrency,
                       coalesce(sum(rejected_concurrency), 0) as rejected_concurrency,
                       coalesce(avg(max_reserved_cpu_millis), 0) as avg_cpu,
                       coalesce(max(max_reserved_cpu_millis), 0) as peak_cpu,
                       coalesce(sum(saturated_cpu_samples), 0) as sat_cpu,
                       coalesce(sum(rejected_cpu), 0) as rejected_cpu,
                       coalesce(avg(max_reserved_memory_bytes), 0) as avg_memory,
                       coalesce(max(max_reserved_memory_bytes), 0) as peak_memory,
                       coalesce(sum(saturated_memory_samples), 0) as sat_memory,
                       coalesce(sum(rejected_memory), 0) as rejected_memory,
                       coalesce(avg(max_backlog), 0) as avg_backlog,
                       coalesce(max(max_backlog), 0) as peak_backlog,
                       coalesce(sum(saturated_backlog_samples), 0) as sat_backlog,
                       coalesce(sum(rejected_backlog), 0) as rejected_backlog,
                       coalesce(avg(max_trigger_rate), 0) as avg_trigger,
                       coalesce(max(max_trigger_rate), 0) as peak_trigger,
                       coalesce(sum(saturated_trigger_rate_samples), 0) as sat_trigger,
                       coalesce(sum(rejected_trigger_rate), 0) as rejected_trigger
                from firefly_tenant_capacity_sample
                where bucket_start>=? and bucket_start<? and sample_count>0
                group by tenant_id order by tenant_id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(from));
            statement.setTimestamp(2, Timestamp.from(to));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String tenantId = result.getString("tenant_id");
                    if (!resultByTenant.containsKey(tenantId)) continue;
                    int observed = result.getInt("observed_buckets");
                    long samples = result.getLong("samples");
                    List<TenantCapacityBaseline.ResourceBaseline> resources = List.of(
                            resource(result, TenantCapacityResource.CONCURRENCY, "concurrency", samples),
                            resource(result, TenantCapacityResource.CPU_MILLIS, "cpu", samples),
                            resource(result, TenantCapacityResource.MEMORY_BYTES, "memory", samples),
                            resource(result, TenantCapacityResource.BACKLOG, "backlog", samples),
                            resource(result, TenantCapacityResource.TRIGGER_RATE, "trigger", samples));
                    String minRuntime = result.getString("min_runtime");
                    String maxRuntime = result.getString("max_runtime");
                    resultByTenant.put(tenantId, new TenantCapacityBaseline(tenantId, from, to,
                            observed, expected, expected == 0 ? 0 : Math.min(1, observed / (double) expected),
                            result.getLong("min_policy"), result.getLong("max_policy"),
                            !Objects.equals(minRuntime, maxRuntime), resources));
                }
            }
            return tenantIds.stream().map(resultByTenant::get).toList();
        } catch (SQLException failure) {
            throw new JdbcException("failed to aggregate tenant capacity report", failure);
        }
    }

    @Override
    public int deleteBefore(Instant cutoff, int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Key> keys = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement("""
                        select tenant_id, bucket_start from firefly_tenant_capacity_sample
                        where bucket_start<? order by bucket_start, tenant_id
                        """)) {
                    select.setTimestamp(1, Timestamp.from(cutoff));
                    select.setMaxRows(limit);
                    try (ResultSet result = select.executeQuery()) {
                        while (result.next()) {
                            keys.add(new Key(result.getString(1), result.getTimestamp(2).toInstant()));
                        }
                    }
                }
                try (PreparedStatement deleteSample = connection.prepareStatement(
                        "delete from firefly_tenant_capacity_sample where tenant_id=? and bucket_start=?");
                     PreparedStatement deleteFlush = connection.prepareStatement(
                             "delete from firefly_tenant_capacity_flush where bucket_start=?")) {
                    for (Key key : keys) {
                        deleteSample.setString(1, key.tenantId());
                        deleteSample.setTimestamp(2, Timestamp.from(key.bucketStart()));
                        deleteSample.addBatch();
                        deleteFlush.setTimestamp(1, Timestamp.from(key.bucketStart()));
                        deleteFlush.addBatch();
                    }
                    deleteSample.executeBatch();
                    deleteFlush.executeBatch();
                }
                connection.commit();
                return keys.size();
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to delete tenant capacity samples", failure);
        }
    }

    private static boolean insertFlush(Connection connection, TenantCapacityFlush flush, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_tenant_capacity_flush(flush_id, bucket_start, created_at) values (?, ?, ?)
                """)) {
            statement.setString(1, flush.flushId());
            statement.setTimestamp(2, Timestamp.from(flush.sample().bucketStart()));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
            return true;
        } catch (SQLException failure) {
            if (constraint(failure)) return false;
            throw failure;
        }
    }

    private static TenantCapacitySample selectSample(
            Connection connection, String tenantId, Instant bucketStart, boolean lock
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAMPLE_COLUMNS
                + " from firefly_tenant_capacity_sample where tenant_id=? and bucket_start=?"
                + (lock ? " for update" : ""))) {
            statement.setString(1, tenantId);
            statement.setTimestamp(2, Timestamp.from(bucketStart));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapSample(result) : null;
            }
        }
    }

    private static void insertSample(Connection connection, TenantCapacitySample sample, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_tenant_capacity_sample(
                    tenant_id, bucket_start, sample_count, min_policy_version, max_policy_version,
                    min_runtime_version, max_runtime_version, max_active_executions,
                    max_reserved_cpu_millis, max_reserved_memory_bytes, max_backlog, max_trigger_rate,
                    saturated_concurrency_samples, saturated_cpu_samples, saturated_memory_samples,
                    saturated_backlog_samples, saturated_trigger_rate_samples, rejected_concurrency,
                    rejected_cpu, rejected_memory, rejected_backlog, rejected_trigger_rate, rejected_disabled,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindSample(statement, sample, now, true);
            statement.executeUpdate();
        }
    }

    private static void updateSample(Connection connection, TenantCapacitySample sample, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_tenant_capacity_sample set
                    sample_count=?, min_policy_version=?, max_policy_version=?, min_runtime_version=?,
                    max_runtime_version=?, max_active_executions=?, max_reserved_cpu_millis=?,
                    max_reserved_memory_bytes=?, max_backlog=?, max_trigger_rate=?,
                    saturated_concurrency_samples=?, saturated_cpu_samples=?, saturated_memory_samples=?,
                    saturated_backlog_samples=?, saturated_trigger_rate_samples=?, rejected_concurrency=?,
                    rejected_cpu=?, rejected_memory=?, rejected_backlog=?, rejected_trigger_rate=?,
                    rejected_disabled=?, updated_at=? where tenant_id=? and bucket_start=?
                """)) {
            int index = bindValues(statement, sample, 1);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setString(index++, sample.tenantId());
            statement.setTimestamp(index, Timestamp.from(sample.bucketStart()));
            if (statement.executeUpdate() != 1) throw new IllegalStateException("capacity sample disappeared");
        }
    }

    private static void bindSample(
            PreparedStatement statement, TenantCapacitySample sample, Instant now, boolean includeCreated
    ) throws SQLException {
        int index = 1;
        statement.setString(index++, sample.tenantId());
        statement.setTimestamp(index++, Timestamp.from(sample.bucketStart()));
        index = bindValues(statement, sample, index);
        if (includeCreated) statement.setTimestamp(index++, Timestamp.from(now));
        statement.setTimestamp(index, Timestamp.from(now));
    }

    private static int bindValues(PreparedStatement statement, TenantCapacitySample value, int index)
            throws SQLException {
        statement.setLong(index++, value.sampleCount());
        statement.setLong(index++, value.minPolicyVersion());
        statement.setLong(index++, value.maxPolicyVersion());
        statement.setString(index++, value.minRuntimeVersion());
        statement.setString(index++, value.maxRuntimeVersion());
        statement.setLong(index++, value.maxActiveExecutions());
        statement.setLong(index++, value.maxReservedCpuMillis());
        statement.setLong(index++, value.maxReservedMemoryBytes());
        statement.setLong(index++, value.maxBacklog());
        statement.setLong(index++, value.maxTriggerRate());
        statement.setLong(index++, value.saturatedConcurrencySamples());
        statement.setLong(index++, value.saturatedCpuSamples());
        statement.setLong(index++, value.saturatedMemorySamples());
        statement.setLong(index++, value.saturatedBacklogSamples());
        statement.setLong(index++, value.saturatedTriggerRateSamples());
        statement.setLong(index++, value.rejectedConcurrency());
        statement.setLong(index++, value.rejectedCpu());
        statement.setLong(index++, value.rejectedMemory());
        statement.setLong(index++, value.rejectedBacklog());
        statement.setLong(index++, value.rejectedTriggerRate());
        statement.setLong(index++, value.rejectedDisabled());
        return index;
    }

    private static TenantCapacitySample mapSample(ResultSet result) throws SQLException {
        return new TenantCapacitySample(result.getString("tenant_id"),
                result.getTimestamp("bucket_start").toInstant(), result.getLong("sample_count"),
                result.getLong("min_policy_version"), result.getLong("max_policy_version"),
                result.getString("min_runtime_version"), result.getString("max_runtime_version"),
                result.getLong("max_active_executions"), result.getLong("max_reserved_cpu_millis"),
                result.getLong("max_reserved_memory_bytes"), result.getLong("max_backlog"),
                result.getLong("max_trigger_rate"), result.getLong("saturated_concurrency_samples"),
                result.getLong("saturated_cpu_samples"), result.getLong("saturated_memory_samples"),
                result.getLong("saturated_backlog_samples"), result.getLong("saturated_trigger_rate_samples"),
                result.getLong("rejected_concurrency"), result.getLong("rejected_cpu"),
                result.getLong("rejected_memory"), result.getLong("rejected_backlog"),
                result.getLong("rejected_trigger_rate"), result.getLong("rejected_disabled"));
    }

    private static TenantCapacityBaseline.ResourceBaseline resource(
            ResultSet result, TenantCapacityResource resource, String suffix, long samples
    ) throws SQLException {
        double saturation = samples == 0 ? 0 : Math.min(1,
                result.getLong("sat_" + suffix) / (double) samples);
        return new TenantCapacityBaseline.ResourceBaseline(resource, result.getDouble("avg_" + suffix),
                result.getLong("peak_" + suffix), saturation, result.getLong("rejected_" + suffix));
    }

    private static TenantCapacitySample mergeSamples(TenantCapacitySample left, TenantCapacitySample right) {
        return new TenantCapacitySample(left.tenantId(), left.bucketStart(), left.sampleCount() + right.sampleCount(),
                minPositive(left.minPolicyVersion(), right.minPolicyVersion()),
                Math.max(left.maxPolicyVersion(), right.maxPolicyVersion()),
                minText(left.minRuntimeVersion(), right.minRuntimeVersion()),
                maxText(left.maxRuntimeVersion(), right.maxRuntimeVersion()),
                Math.max(left.maxActiveExecutions(), right.maxActiveExecutions()),
                Math.max(left.maxReservedCpuMillis(), right.maxReservedCpuMillis()),
                Math.max(left.maxReservedMemoryBytes(), right.maxReservedMemoryBytes()),
                Math.max(left.maxBacklog(), right.maxBacklog()), Math.max(left.maxTriggerRate(), right.maxTriggerRate()),
                left.saturatedConcurrencySamples() + right.saturatedConcurrencySamples(),
                left.saturatedCpuSamples() + right.saturatedCpuSamples(),
                left.saturatedMemorySamples() + right.saturatedMemorySamples(),
                left.saturatedBacklogSamples() + right.saturatedBacklogSamples(),
                left.saturatedTriggerRateSamples() + right.saturatedTriggerRateSamples(),
                left.rejectedConcurrency() + right.rejectedConcurrency(), left.rejectedCpu() + right.rejectedCpu(),
                left.rejectedMemory() + right.rejectedMemory(), left.rejectedBacklog() + right.rejectedBacklog(),
                left.rejectedTriggerRate() + right.rejectedTriggerRate(),
                left.rejectedDisabled() + right.rejectedDisabled());
    }

    private static int expectedBuckets(Instant from, Instant to, Duration width) {
        if (width == null || width.isZero() || width.isNegative()) throw new IllegalArgumentException("bucketWidth");
        long milliseconds = Duration.between(from, to).toMillis();
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, (milliseconds + width.toMillis() - 1) / width.toMillis()));
    }

    private static long minPositive(long left, long right) {
        if (left == 0) return right;
        if (right == 0) return left;
        return Math.min(left, right);
    }

    private static String minText(String left, String right) { return left.compareTo(right) <= 0 ? left : right; }
    private static String maxText(String left, String right) { return left.compareTo(right) >= 0 ? left : right; }
    private static boolean constraint(SQLException failure) {
        return "23505".equals(failure.getSQLState()) || "23000".equals(failure.getSQLState());
    }
    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    private static final String SAMPLE_COLUMNS = """
            select tenant_id, bucket_start, sample_count, min_policy_version, max_policy_version,
                   min_runtime_version, max_runtime_version, max_active_executions,
                   max_reserved_cpu_millis, max_reserved_memory_bytes, max_backlog, max_trigger_rate,
                   saturated_concurrency_samples, saturated_cpu_samples, saturated_memory_samples,
                   saturated_backlog_samples, saturated_trigger_rate_samples, rejected_concurrency,
                   rejected_cpu, rejected_memory, rejected_backlog, rejected_trigger_rate, rejected_disabled
            """;

    private record Key(String tenantId, Instant bucketStart) { }
}
