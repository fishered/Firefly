package com.firefly.store.jdbc;

import com.firefly.trigger.*;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

public final class JdbcTriggerInbox implements TriggerInbox {
    private final DataSource dataSource;
    public JdbcTriggerInbox(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource,"dataSource"); }
    public boolean receive(EventTrigger e) {
        try (var c=dataSource.getConnection(); var p=c.prepareStatement("insert into firefly_trigger_inbox(event_id,event_type,idempotency_key,payload,status,received_at) values(?,?,?,?,?,?)")) {
            p.setString(1,e.eventId());p.setString(2,e.eventType());p.setString(3,e.idempotencyKey());p.setString(4,e.payload());p.setString(5,e.status().name());p.setTimestamp(6,Timestamp.from(e.receivedAt()));p.executeUpdate();return true;
        } catch (SQLException x) { if (isConstraint(x)) return false; throw new JdbcException("failed to receive trigger",x); }
    }
    public Optional<EventTrigger> findByIdempotencyKey(String key) {
        try (var c=dataSource.getConnection();var p=c.prepareStatement("select event_id,event_type,idempotency_key,payload,status,received_at,processed_at from firefly_trigger_inbox where idempotency_key=?")){p.setString(1,key);try(var r=p.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new EventTrigger(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(6).toInstant(),EventTrigger.TriggerStatus.valueOf(r.getString(5)),r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant()));}}
        catch(SQLException x){throw new JdbcException("failed to read trigger",x);}
    }
    public boolean markProcessed(String key, Instant at){return mark(key,EventTrigger.TriggerStatus.PROCESSED,at);}
    public boolean markFailed(String key, Instant at){return mark(key,EventTrigger.TriggerStatus.FAILED,at);}
    private boolean mark(String key, EventTrigger.TriggerStatus status, Instant at){try(var c=dataSource.getConnection();var p=c.prepareStatement("update firefly_trigger_inbox set status=?,processed_at=? where idempotency_key=? and status=?")){p.setString(1,status.name());p.setTimestamp(2,Timestamp.from(at));p.setString(3,key);p.setString(4,EventTrigger.TriggerStatus.RECEIVED.name());return p.executeUpdate()==1;}catch(SQLException x){throw new JdbcException("failed to update trigger",x);}}
    private boolean isConstraint(SQLException e){String s=e.getSQLState();return "23505".equals(s)||"23000".equals(s)||"23505".equalsIgnoreCase(s);}
}
