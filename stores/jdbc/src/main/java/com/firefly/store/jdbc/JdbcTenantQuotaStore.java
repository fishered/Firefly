package com.firefly.store.jdbc;

import com.firefly.governance.TenantAdmissionDecision;
import com.firefly.governance.TenantAdmissionReason;
import com.firefly.governance.TenantAdmissionRequest;
import com.firefly.governance.TenantDefinition;
import com.firefly.governance.TenantQuotaStore;
import com.firefly.governance.TenantReservation;
import com.firefly.governance.TenantUsage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Database-backed tenant quota ledger. Policy and usage rows serialize admissions across cluster nodes. */
public final class JdbcTenantQuotaStore implements TenantQuotaStore {
    private final DataSource dataSource;

    public JdbcTenantQuotaStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public TenantDefinition save(TenantDefinition definition, long expectedVersion, Instant now) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantDefinition current = selectPolicy(connection, definition.tenantId(), true);
                long actual = current == null ? 0 : current.version();
                if (actual != expectedVersion) {
                    throw new IllegalStateException("tenant version conflict: expected " + expectedVersion
                            + " but was " + actual);
                }
                TenantDefinition saved = definition.withVersion(Math.addExact(actual, 1), now);
                if (current == null) {
                    insertPolicy(connection, saved);
                    insertUsage(connection, TenantUsage.empty(saved.tenantId(), now));
                } else {
                    updatePolicy(connection, saved, expectedVersion);
                }
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            if (constraint(failure)) {
                throw new IllegalStateException("tenant version conflict", failure);
            }
            throw new JdbcException("failed to save tenant policy", failure);
        }
    }

    @Override
    public Optional<TenantDefinition> find(String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            return Optional.ofNullable(selectPolicy(connection, normalizeTenant(tenantId), false));
        } catch (SQLException failure) {
            throw new JdbcException("failed to find tenant policy", failure);
        }
    }

    @Override
    public List<TenantDefinition> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(POLICY_COLUMNS
                     + " from firefly_tenant_policy order by tenant_id")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TenantDefinition> result = new ArrayList<>();
                while (resultSet.next()) result.add(mapPolicy(resultSet));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to list tenant policies", failure);
        }
    }

    @Override
    public TenantUsage usage(String tenantId, Instant now) {
        String normalized = normalizeTenant(tenantId);
        try (Connection connection = dataSource.getConnection()) {
            TenantUsage usage = selectUsage(connection, normalized, false);
            return rollWindow(usage == null ? TenantUsage.empty(normalized, now) : usage, now);
        } catch (SQLException failure) {
            throw new JdbcException("failed to read tenant usage", failure);
        }
    }

    @Override
    public TenantAdmissionDecision tryReserve(
            TenantAdmissionRequest request, Instant now, Duration leaseDuration
    ) {
        validateLease(leaseDuration);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantDefinition policy = selectPolicy(connection, request.tenantId(), true);
                TenantUsage current = policy == null
                        ? TenantUsage.empty(request.tenantId(), now)
                        : selectUsage(connection, request.tenantId(), true);
                if (policy == null) {
                    connection.commit();
                    return TenantAdmissionDecision.rejected(TenantAdmissionReason.TENANT_NOT_FOUND, current);
                }
                current = reclaimExpiredForTenant(connection, current, now);
                TenantReservation previous = selectReservation(connection, request.reservationId(), true);
                if (previous != null) {
                    validateReplay(previous, request);
                    Instant renewedUntil = now.plus(leaseDuration);
                    previous = new TenantReservation(previous.reservationId(), previous.tenantId(),
                            previous.concurrentExecutions(), previous.cpuMillis(), previous.memoryBytes(),
                            previous.backlog(), previous.triggers(), now,
                            renewedUntil.isAfter(previous.expiresAt()) ? renewedUntil : previous.expiresAt());
                    refreshReservation(connection, previous.reservationId(), previous.createdAt(),
                            previous.expiresAt());
                    connection.commit();
                    return TenantAdmissionDecision.admitted(TenantAdmissionReason.IDEMPOTENT_REPLAY,
                            rollWindow(current, now), previous);
                }
                current = rollWindow(current, now);
                TenantAdmissionReason rejection = rejection(policy, current, request);
                if (rejection != null) {
                    if (!sameUsage(current, selectUsage(connection, request.tenantId(), false))) {
                        updateUsage(connection, current);
                    }
                    connection.commit();
                    return TenantAdmissionDecision.rejected(rejection, current);
                }
                TenantUsage updated = new TenantUsage(request.tenantId(),
                        Math.addExact(current.activeExecutions(), request.concurrentExecutions()),
                        Math.addExact(current.reservedCpuMillis(), request.cpuMillis()),
                        Math.addExact(current.reservedMemoryBytes(), request.memoryBytes()),
                        Math.addExact(current.backlog(), request.backlog()), current.triggerWindowStartedAt(),
                        Math.addExact(current.triggersInWindow(), request.triggers()),
                        Math.addExact(current.version(), 1));
                TenantReservation reservation = new TenantReservation(request.reservationId(), request.tenantId(),
                        request.concurrentExecutions(), request.cpuMillis(), request.memoryBytes(), request.backlog(),
                        request.triggers(), now, now.plus(leaseDuration));
                insertReservation(connection, reservation);
                updateUsage(connection, updated);
                connection.commit();
                return TenantAdmissionDecision.admitted(TenantAdmissionReason.ADMITTED, updated, reservation);
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to reserve tenant quota", failure);
        }
    }

    @Override
    public boolean release(String reservationId, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantReservation candidate = selectReservation(connection, reservationId, false);
                if (candidate == null) {
                    connection.commit();
                    return false;
                }
                selectPolicy(connection, candidate.tenantId(), true);
                TenantUsage usage = selectUsage(connection, candidate.tenantId(), true);
                TenantReservation reservation = selectReservation(connection, reservationId, true);
                if (reservation == null) {
                    connection.commit();
                    return false;
                }
                updateUsage(connection, released(rollWindow(usage, now), reservation));
                deleteReservation(connection, reservationId);
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to release tenant reservation", failure);
        }
    }

    @Override
    public int reclaimExpired(Instant now, int limit) {
        if (limit < 1) return 0;
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select reservation_id from firefly_tenant_reservation
                     where expires_at<=? order by expires_at, reservation_id limit ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) ids.add(resultSet.getString(1));
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to list expired tenant reservations", failure);
        }
        int reclaimed = 0;
        for (String id : ids) if (release(id, now)) reclaimed++;
        return reclaimed;
    }

    @Override
    public int reconcileBacklogReservations(Instant now, Duration orphanGrace, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(orphanGrace, "orphanGrace");
        if (orphanGrace.isNegative()) throw new IllegalArgumentException("orphanGrace must not be negative");
        if (limit < 1) return 0;
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select reservation.reservation_id
                     from firefly_tenant_reservation reservation
                     where reservation.backlog>0
                       and reservation.concurrent_executions=0
                       and reservation.cpu_millis=0
                       and reservation.memory_bytes=0
                       and reservation.triggers=0
                       and reservation.created_at<=?
                       and not exists (
                           select 1 from firefly_dispatch_outbox outbox
                           where outbox.outbox_id=reservation.reservation_id
                             and outbox.status not in ('DONE','DEAD')
                       )
                     order by reservation.created_at, reservation.reservation_id
                     limit ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now.minus(orphanGrace)));
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) ids.add(resultSet.getString(1));
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to find orphaned tenant backlog reservations", failure);
        }
        Instant cutoff = now.minus(orphanGrace);
        int reconciled = 0;
        for (String id : ids) if (reconcileBacklogReservation(id, cutoff, now)) reconciled++;
        return reconciled;
    }

    private boolean reconcileBacklogReservation(String reservationId, Instant cutoff, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantReservation candidate = selectReservation(connection, reservationId, false);
                if (candidate == null) {
                    connection.commit();
                    return false;
                }
                selectPolicy(connection, candidate.tenantId(), true);
                TenantUsage usage = selectUsage(connection, candidate.tenantId(), true);
                TenantReservation reservation = selectReservation(connection, reservationId, true);
                if (reservation == null || reservation.createdAt().isAfter(cutoff)
                        || reservation.backlog() <= 0 || reservation.concurrentExecutions() != 0
                        || reservation.cpuMillis() != 0 || reservation.memoryBytes() != 0
                        || reservation.triggers() != 0 || activeWorkForUpdate(connection, reservationId)) {
                    connection.commit();
                    return false;
                }
                updateUsage(connection, released(rollWindow(usage, now), reservation));
                deleteReservation(connection, reservationId);
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (SQLException) failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to reconcile tenant backlog reservation", failure);
        }
    }

    private static boolean activeWorkForUpdate(Connection connection, String outboxId) throws SQLException {
        String executionId;
        try (PreparedStatement statement = connection.prepareStatement("""
                select status, execution_id from firefly_dispatch_outbox where outbox_id=? for update
                """)) {
            statement.setString(1, outboxId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return false;
                String status = resultSet.getString(1);
                if ("DEAD".equals(status)) return false;
                if (!"DONE".equals(status)) return true;
                executionId = resultSet.getString(2);
            }
        }
        // DONE means delivery was acknowledged, not that remote execution finished. Execution
        // terminal states never regress, so a plain read is sufficient: a concurrent transition
        // can only retain the reservation for one extra reconciliation pass.
        try (PreparedStatement statement = connection.prepareStatement(
                "select status from firefly_execution where execution_id=?")) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return false;
                return switch (resultSet.getString(1)) {
                    case "SUCCEEDED", "PARTIAL", "FAILED", "TIMEOUT", "CANCELLED" -> false;
                    default -> true;
                };
            }
        }
    }

    private TenantUsage reclaimExpiredForTenant(Connection connection, TenantUsage current, Instant now)
            throws SQLException {
        List<TenantReservation> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select reservation_id, tenant_id, concurrent_executions, cpu_millis, memory_bytes,
                       backlog, triggers, created_at, expires_at
                from firefly_tenant_reservation
                where tenant_id=? and expires_at<=?
                order by expires_at, reservation_id for update
                """)) {
            statement.setString(1, current.tenantId());
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) expired.add(mapReservation(resultSet));
            }
        }
        TenantUsage updated = current;
        for (TenantReservation reservation : expired) {
            updated = released(updated, reservation);
            deleteReservation(connection, reservation.reservationId());
        }
        if (!expired.isEmpty()) updateUsage(connection, updated);
        return updated;
    }

    private static TenantUsage released(TenantUsage usage, TenantReservation reservation) {
        return new TenantUsage(usage.tenantId(),
                Math.max(0, usage.activeExecutions() - reservation.concurrentExecutions()),
                Math.max(0, usage.reservedCpuMillis() - reservation.cpuMillis()),
                Math.max(0, usage.reservedMemoryBytes() - reservation.memoryBytes()),
                Math.max(0, usage.backlog() - reservation.backlog()), usage.triggerWindowStartedAt(),
                usage.triggersInWindow(), Math.addExact(usage.version(), 1));
    }

    private static TenantAdmissionReason rejection(TenantDefinition definition, TenantUsage usage,
                                                   TenantAdmissionRequest request) {
        if (!definition.enabled()) return TenantAdmissionReason.TENANT_DISABLED;
        if (exceeds(definition.maxConcurrent(), usage.activeExecutions(), request.concurrentExecutions()))
            return TenantAdmissionReason.CONCURRENCY_QUOTA_EXCEEDED;
        if (exceeds(definition.maxCpuMillis(), usage.reservedCpuMillis(), request.cpuMillis()))
            return TenantAdmissionReason.CPU_QUOTA_EXCEEDED;
        if (exceeds(definition.maxMemoryBytes(), usage.reservedMemoryBytes(), request.memoryBytes()))
            return TenantAdmissionReason.MEMORY_QUOTA_EXCEEDED;
        if (exceeds(definition.maxBacklog(), usage.backlog(), request.backlog()))
            return TenantAdmissionReason.BACKLOG_QUOTA_EXCEEDED;
        if (exceeds(definition.maxTriggerRatePerSecond(), usage.triggersInWindow(), request.triggers()))
            return TenantAdmissionReason.TRIGGER_RATE_QUOTA_EXCEEDED;
        return null;
    }

    private static TenantUsage rollWindow(TenantUsage usage, Instant now) {
        Instant window = now.truncatedTo(ChronoUnit.SECONDS);
        // A slow gateway must not move the shared rate window backwards and regain tokens.
        if (!window.isAfter(usage.triggerWindowStartedAt())) return usage;
        return new TenantUsage(usage.tenantId(), usage.activeExecutions(), usage.reservedCpuMillis(),
                usage.reservedMemoryBytes(), usage.backlog(), window, 0, usage.version());
    }

    private static boolean exceeds(long limit, long current, long requested) {
        return limit > 0 && (requested > limit || current > limit - requested);
    }

    private static final String POLICY_COLUMNS = """
            select tenant_id, enabled, weight, max_concurrent, max_cpu_millis, max_memory_bytes,
                   max_backlog, max_trigger_rate, version, updated_at
            """;

    private static TenantDefinition selectPolicy(Connection connection, String tenantId, boolean lock)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(POLICY_COLUMNS
                + " from firefly_tenant_policy where tenant_id=?" + (lock ? " for update" : ""))) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapPolicy(resultSet) : null;
            }
        }
    }

    private static TenantUsage selectUsage(Connection connection, String tenantId, boolean lock)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select tenant_id, active_executions, reserved_cpu_millis, reserved_memory_bytes,
                       backlog, trigger_window_started_at, triggers_in_window, version
                from firefly_tenant_usage where tenant_id=?
                """ + (lock ? " for update" : ""))) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapUsage(resultSet) : null;
            }
        }
    }

    private static TenantReservation selectReservation(Connection connection, String id, boolean lock)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select reservation_id, tenant_id, concurrent_executions, cpu_millis, memory_bytes,
                       backlog, triggers, created_at, expires_at
                from firefly_tenant_reservation where reservation_id=?
                """ + (lock ? " for update" : ""))) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapReservation(resultSet) : null;
            }
        }
    }

    private static void insertPolicy(Connection connection, TenantDefinition value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_tenant_policy
                (tenant_id, enabled, weight, max_concurrent, max_cpu_millis, max_memory_bytes,
                 max_backlog, max_trigger_rate, version, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindPolicy(statement, value);
            statement.executeUpdate();
        }
    }

    private static void updatePolicy(Connection connection, TenantDefinition value, long expectedVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_tenant_policy set enabled=?, weight=?, max_concurrent=?, max_cpu_millis=?,
                    max_memory_bytes=?, max_backlog=?, max_trigger_rate=?, version=?, updated_at=?
                where tenant_id=? and version=?
                """)) {
            statement.setBoolean(1, value.enabled()); statement.setInt(2, value.weight());
            statement.setInt(3, value.maxConcurrent()); statement.setLong(4, value.maxCpuMillis());
            statement.setLong(5, value.maxMemoryBytes()); statement.setLong(6, value.maxBacklog());
            statement.setLong(7, value.maxTriggerRatePerSecond()); statement.setLong(8, value.version());
            statement.setTimestamp(9, Timestamp.from(value.updatedAt())); statement.setString(10, value.tenantId());
            statement.setLong(11, expectedVersion);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("tenant version conflict");
        }
    }

    private static void bindPolicy(PreparedStatement statement, TenantDefinition value) throws SQLException {
        statement.setString(1, value.tenantId()); statement.setBoolean(2, value.enabled());
        statement.setInt(3, value.weight()); statement.setInt(4, value.maxConcurrent());
        statement.setLong(5, value.maxCpuMillis()); statement.setLong(6, value.maxMemoryBytes());
        statement.setLong(7, value.maxBacklog()); statement.setLong(8, value.maxTriggerRatePerSecond());
        statement.setLong(9, value.version()); statement.setTimestamp(10, Timestamp.from(value.updatedAt()));
    }

    private static void insertUsage(Connection connection, TenantUsage usage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_tenant_usage
                (tenant_id, active_executions, reserved_cpu_millis, reserved_memory_bytes, backlog,
                 trigger_window_started_at, triggers_in_window, version) values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindUsage(statement, usage);
            statement.executeUpdate();
        }
    }

    private static void updateUsage(Connection connection, TenantUsage usage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_tenant_usage set active_executions=?, reserved_cpu_millis=?,
                    reserved_memory_bytes=?, backlog=?, trigger_window_started_at=?, triggers_in_window=?,
                    version=? where tenant_id=?
                """)) {
            statement.setInt(1, usage.activeExecutions()); statement.setLong(2, usage.reservedCpuMillis());
            statement.setLong(3, usage.reservedMemoryBytes()); statement.setLong(4, usage.backlog());
            statement.setTimestamp(5, Timestamp.from(usage.triggerWindowStartedAt()));
            statement.setLong(6, usage.triggersInWindow()); statement.setLong(7, usage.version());
            statement.setString(8, usage.tenantId());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("tenant usage row is missing");
        }
    }

    private static void bindUsage(PreparedStatement statement, TenantUsage usage) throws SQLException {
        statement.setString(1, usage.tenantId()); statement.setInt(2, usage.activeExecutions());
        statement.setLong(3, usage.reservedCpuMillis()); statement.setLong(4, usage.reservedMemoryBytes());
        statement.setLong(5, usage.backlog()); statement.setTimestamp(6, Timestamp.from(usage.triggerWindowStartedAt()));
        statement.setLong(7, usage.triggersInWindow()); statement.setLong(8, usage.version());
    }

    private static void insertReservation(Connection connection, TenantReservation value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_tenant_reservation
                (reservation_id, tenant_id, concurrent_executions, cpu_millis, memory_bytes, backlog,
                 triggers, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, value.reservationId()); statement.setString(2, value.tenantId());
            statement.setInt(3, value.concurrentExecutions()); statement.setLong(4, value.cpuMillis());
            statement.setLong(5, value.memoryBytes()); statement.setLong(6, value.backlog());
            statement.setLong(7, value.triggers()); statement.setTimestamp(8, Timestamp.from(value.createdAt()));
            statement.setTimestamp(9, Timestamp.from(value.expiresAt())); statement.executeUpdate();
        }
    }

    private static void deleteReservation(Connection connection, String reservationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from firefly_tenant_reservation where reservation_id=?")) {
            statement.setString(1, reservationId);
            statement.executeUpdate();
        }
    }

    private static void refreshReservation(
            Connection connection, String reservationId, Instant refreshedAt, Instant expiresAt
    )
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update firefly_tenant_reservation set created_at=?, expires_at=? where reservation_id=?")) {
            statement.setTimestamp(1, Timestamp.from(refreshedAt));
            statement.setTimestamp(2, Timestamp.from(expiresAt));
            statement.setString(3, reservationId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("tenant reservation is missing");
        }
    }

    private static TenantDefinition mapPolicy(ResultSet resultSet) throws SQLException {
        return new TenantDefinition(resultSet.getString("tenant_id"), resultSet.getBoolean("enabled"),
                resultSet.getInt("weight"), resultSet.getInt("max_concurrent"),
                resultSet.getLong("max_cpu_millis"), resultSet.getLong("max_memory_bytes"),
                resultSet.getLong("max_backlog"), resultSet.getLong("max_trigger_rate"),
                resultSet.getLong("version"), resultSet.getTimestamp("updated_at").toInstant());
    }

    private static TenantUsage mapUsage(ResultSet resultSet) throws SQLException {
        return new TenantUsage(resultSet.getString("tenant_id"), resultSet.getInt("active_executions"),
                resultSet.getLong("reserved_cpu_millis"), resultSet.getLong("reserved_memory_bytes"),
                resultSet.getLong("backlog"), resultSet.getTimestamp("trigger_window_started_at").toInstant(),
                resultSet.getLong("triggers_in_window"), resultSet.getLong("version"));
    }

    private static TenantReservation mapReservation(ResultSet resultSet) throws SQLException {
        return new TenantReservation(resultSet.getString("reservation_id"), resultSet.getString("tenant_id"),
                resultSet.getInt("concurrent_executions"), resultSet.getLong("cpu_millis"),
                resultSet.getLong("memory_bytes"), resultSet.getLong("backlog"), resultSet.getLong("triggers"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant());
    }

    private static boolean sameUsage(TenantUsage first, TenantUsage second) {
        return first.equals(second);
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? TenantDefinition.DEFAULT_TENANT_ID : tenantId;
    }

    private static void validateLease(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void validateReplay(TenantReservation reservation, TenantAdmissionRequest request) {
        if (!reservation.tenantId().equals(request.tenantId())
                || reservation.concurrentExecutions() != request.concurrentExecutions()
                || reservation.cpuMillis() != request.cpuMillis()
                || reservation.memoryBytes() != request.memoryBytes()
                || reservation.backlog() != request.backlog()
                || reservation.triggers() != request.triggers()) {
            throw new IllegalArgumentException("reservationId is already used for different resource units");
        }
    }

    private static boolean constraint(SQLException failure) {
        return "23505".equals(failure.getSQLState()) || "23000".equals(failure.getSQLState());
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
