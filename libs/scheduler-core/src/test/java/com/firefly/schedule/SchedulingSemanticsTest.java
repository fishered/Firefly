package com.firefly.schedule;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingSemanticsTest {
    @Test void skipsHolidayAndDelaysBlackout() {
        CalendarDefinition calendar = new CalendarDefinition("cn", 1, ZoneId.of("Asia/Shanghai"),
                EnumSet.of(DayOfWeek.MONDAY), Set.of(LocalDate.of(2026, 8, 31)), Set.of());
        Instant holiday = LocalDateTime.of(2026, 8, 31, 10, 0).atZone(calendar.zoneId()).toInstant();
        SchedulingSemanticsEvaluator evaluator = new SchedulingSemanticsEvaluator();
        assertEquals(SchedulingDecision.Decision.SKIP, evaluator.evaluate(holiday, calendar, List.of(), calendar.zoneId()).decision());
        Instant monday = LocalDateTime.of(2026, 9, 7, 10, 30).atZone(calendar.zoneId()).toInstant();
        BlackoutWindow window = new BlackoutWindow("deploy", Set.of(DayOfWeek.MONDAY), LocalTime.of(10,0), LocalTime.of(11,0), BlackoutAction.DELAY_TO_END);
        SchedulingDecision delayed = evaluator.evaluate(monday, null, List.of(window), calendar.zoneId());
        assertEquals(SchedulingDecision.Decision.DELAY, delayed.decision());
        assertEquals(LocalTime.of(11,0), delayed.effectiveTime().atZone(calendar.zoneId()).toLocalTime());
    }
    @Test void rejectsDependencyCycle() {
        assertThrows(IllegalArgumentException.class, () -> new DependencyGraphValidator().validate(List.of(
                new JobDependency("a", "b", 1), new JobDependency("b", "a", 1))));
    }
}
