package com.firefly.schedule;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCalendarRepository implements CalendarRepository {
    private final Map<String, CalendarDefinition> calendars = new ConcurrentHashMap<>();
    public void save(CalendarDefinition calendar) { calendars.compute(calendar.id(), (ignored, current) -> current == null || calendar.version() > current.version() ? calendar : current); }
    public Optional<CalendarDefinition> find(String id) { return Optional.ofNullable(calendars.get(id)); }
}
