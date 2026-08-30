package com.firefly.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Applies calendar first and blackout windows second, before execution creation. */
public final class SchedulingSemanticsEvaluator {
    private final CalendarEvaluator calendars;

    public SchedulingSemanticsEvaluator() { this(new CalendarEvaluator()); }
    public SchedulingSemanticsEvaluator(CalendarEvaluator calendars) { this.calendars = Objects.requireNonNull(calendars, "calendars"); }

    public SchedulingDecision evaluate(Instant fireTime, CalendarDefinition calendar,
                                       List<BlackoutWindow> blackouts, java.time.ZoneId zoneId) {
        Objects.requireNonNull(fireTime, "fireTime");
        Objects.requireNonNull(blackouts, "blackouts");
        Objects.requireNonNull(zoneId, "zoneId");
        if (calendar != null && !calendars.isWorkingInstant(calendar, fireTime)) {
            return new SchedulingDecision(SchedulingDecision.Decision.SKIP, fireTime, fireTime, "non_working_day");
        }
        for (BlackoutWindow window : blackouts) {
            if (window.contains(fireTime, zoneId)) {
                if (window.action() == BlackoutAction.SKIP) {
                    return new SchedulingDecision(SchedulingDecision.Decision.SKIP, fireTime, fireTime,
                            "blackout:" + window.id());
                }
                return new SchedulingDecision(SchedulingDecision.Decision.DELAY, fireTime,
                        window.endInstant(fireTime, zoneId), "blackout_delay:" + window.id());
            }
        }
        return new SchedulingDecision(SchedulingDecision.Decision.FIRE, fireTime, fireTime, "eligible");
    }
}
