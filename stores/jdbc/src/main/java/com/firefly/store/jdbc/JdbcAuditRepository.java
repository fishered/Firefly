package com.firefly.store.jdbc;

import com.firefly.audit.AuditRecord;
import com.firefly.audit.AuditRepository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcAuditRepository implements AuditRepository {
    private final DataSource dataSource;

    public JdbcAuditRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void append(AuditRecord record) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into firefly_audit_log
                     (audit_id, occurred_at, actor, role_name, action_name, resource_type, resource_id,
                      outcome, before_payload, after_payload, detail)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, record.auditId());
            statement.setTimestamp(2, Timestamp.from(record.occurredAt()));
            statement.setString(3, record.actor());
            statement.setString(4, record.role());
            statement.setString(5, record.action());
            statement.setString(6, record.resourceType());
            statement.setString(7, record.resourceId());
            statement.setString(8, record.outcome());
            statement.setString(9, record.beforePayload());
            statement.setString(10, record.afterPayload());
            statement.setString(11, record.detail());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcException("failed to append audit record", e);
        }
    }

    @Override
    public List<AuditRecord> listRecent(int limit) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select audit_id, occurred_at, actor, role_name, action_name, resource_type,
                            resource_id, outcome, before_payload, after_payload, detail
                     from firefly_audit_log
                     order by occurred_at desc, audit_id desc
                     limit ?
                     """)) {
            statement.setInt(1, Math.max(0, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuditRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new AuditRecord(
                            resultSet.getString("audit_id"),
                            resultSet.getTimestamp("occurred_at").toInstant(),
                            resultSet.getString("actor"), resultSet.getString("role_name"),
                            resultSet.getString("action_name"), resultSet.getString("resource_type"),
                            resultSet.getString("resource_id"), resultSet.getString("outcome"),
                            resultSet.getString("before_payload"), resultSet.getString("after_payload"),
                            resultSet.getString("detail")
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list audit records", e);
        }
    }
}
