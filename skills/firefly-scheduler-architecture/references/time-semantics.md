# Firefly Time Semantics

Use this reference for cron, time-zone, DST, one-shot scheduling, and schedule validation work.

## Core Model

Firefly separates business time from runtime time:

- Business schedule: cron or schedule expression plus explicit `ZoneId`.
- Runtime cursor: UTC `Instant`.
- Display time: convert `Instant` back to the job's `ZoneId`.

This avoids scheduler-server default time-zone bugs.

## Cron Semantics

A cron schedule means local wall-clock time in the job region.

Example:

```java
new CronSchedule("0 0 9 * * *")
ZoneId.of("America/New_York")
```

This means 09:00 New York local time.

The equivalent UTC instant changes across seasons:

- Summer in New York: 09:00 local is usually 13:00 UTC.
- Winter in New York: 09:00 local is usually 14:00 UTC.

Do not describe this as "UTC 09:00".

## DST Gap

A DST gap happens when local clock time jumps forward and some local times do not exist.

Example: `America/New_York` spring-forward day can skip local `02:00-02:59`.

If a cron asks for `02:30`, current default behavior is:

- skip that nonexistent occurrence;
- continue with the next valid matching local time.

For one-shot schedules using a specific `LocalDateTime`, creation should validate immediately. If the local time does not exist, require the caller to choose a policy or change the time.

## DST Overlap

A DST overlap happens when local clock time moves backward and some local times occur twice.

Example: `America/New_York` fall-back day can contain two different `01:30` instants.

Current default behavior:

- both real instants can fire;
- store and compare them as different UTC `Instant` values.

## Validation Guidance

For periodic schedules, do not reject a cron only because one future date has a gap or overlap. Instead, return warnings and a preview.

Recommended validation result concepts:

```java
ScheduleValidationResult
ScheduleWarning
ScheduleWarningType.DST_GAP
ScheduleWarningType.DST_OVERLAP
```

Recommended preview:

- next 5-10 fire times;
- local time with zone and offset;
- corresponding UTC `Instant`;
- warning when a local occurrence is skipped or duplicated.

## Future DST Policy

When product requirements need explicit control, add job-level `DstPolicy`.

Suggested starting point:

```java
public enum DstPolicy {
    SKIP_GAP_AND_FIRE_OVERLAP_TWICE,
    SHIFT_GAP_FORWARD_AND_FIRE_OVERLAP_ONCE,
    SKIP_GAP_AND_FIRE_OVERLAP_EARLIER,
    SKIP_GAP_AND_FIRE_OVERLAP_LATER
}
```

Keep defaults conservative and documented:

- gap: skip;
- overlap: fire twice.

## Tests To Add For Time Changes

Add focused tests for:

- normal zone conversion;
- spring-forward nonexistent local time;
- fall-back repeated local time;
- fixed-rate schedule unaffected by local wall-clock transitions;
- validation warnings for DST gap and overlap;
- one-shot local time validation when added.
