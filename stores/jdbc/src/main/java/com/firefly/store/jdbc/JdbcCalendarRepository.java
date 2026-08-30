package com.firefly.store.jdbc;

import com.firefly.schedule.CalendarDefinition;
import com.firefly.schedule.CalendarRepository;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcCalendarRepository implements CalendarRepository {
    private final DataSource dataSource;
    public JdbcCalendarRepository(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }
    public void save(CalendarDefinition c) {
        String sql = "insert into firefly_calendar(calendar_id,calendar_version,zone_id,working_days,holidays,extra_working_days) values(?,?,?,?,?,?)";
        try (var con = dataSource.getConnection(); var ps = con.prepareStatement(sql)) {
            ps.setString(1,c.id()); ps.setLong(2,c.version()); ps.setString(3,c.zoneId().getId()); ps.setString(4, encodeDays(c.workingDays())); ps.setString(5, encodeDates(c.holidays())); ps.setString(6, encodeDates(c.extraWorkingDays())); ps.executeUpdate();
        } catch (SQLException e) { throw new JdbcException("failed to save calendar", e); }
    }
    public Optional<CalendarDefinition> find(String id) {
        String sql = "select calendar_id,calendar_version,zone_id,working_days,holidays,extra_working_days from firefly_calendar where calendar_id=? order by calendar_version desc";
        try (var con = dataSource.getConnection(); var ps = con.prepareStatement(sql)) { ps.setString(1,id); try (var rs=ps.executeQuery()) { if (!rs.next()) return Optional.empty(); return Optional.of(new CalendarDefinition(rs.getString(1),rs.getLong(2),ZoneId.of(rs.getString(3)),decodeDays(rs.getString(4)),decodeDates(rs.getString(5)),decodeDates(rs.getString(6)))); } }
        catch (SQLException e) { throw new JdbcException("failed to read calendar", e); }
    }
    public List<CalendarDefinition> list() {
        List<CalendarDefinition> result = new ArrayList<>();
        String sql = "select calendar_id,calendar_version,zone_id,working_days,holidays,extra_working_days from firefly_calendar order by calendar_id,calendar_version desc";
        try (var con=dataSource.getConnection(); var ps=con.prepareStatement(sql); var rs=ps.executeQuery()) {
            while (rs.next()) result.add(new CalendarDefinition(rs.getString(1),rs.getLong(2),ZoneId.of(rs.getString(3)),decodeDays(rs.getString(4)),decodeDates(rs.getString(5)),decodeDates(rs.getString(6))));
            return List.copyOf(result);
        } catch (SQLException e) { throw new JdbcException("failed to list calendars", e); }
    }
    private static String encodeDays(Set<DayOfWeek> values) { return values.stream().map(Enum::name).sorted().reduce((a,b)->a+","+b).orElse(""); }
    private static String encodeDates(Set<LocalDate> values) { return values.stream().map(LocalDate::toString).sorted().reduce((a,b)->a+","+b).orElse(""); }
    private static Set<DayOfWeek> decodeDays(String v) { Set<DayOfWeek> r=EnumSet.noneOf(DayOfWeek.class); if(v!=null&&!v.isBlank()) for(String x:v.split(",")) r.add(DayOfWeek.valueOf(x)); return r; }
    private static Set<LocalDate> decodeDates(String v) { if(v==null||v.isBlank()) return Set.of(); Set<LocalDate> r=new HashSet<>(); for(String x:v.split(",")) r.add(LocalDate.parse(x)); return Set.copyOf(r); }
}
