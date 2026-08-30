package com.firefly.schedule;

import java.util.Optional;

public interface CalendarRepository {
    void save(CalendarDefinition calendar);
    Optional<CalendarDefinition> find(String id);
}
