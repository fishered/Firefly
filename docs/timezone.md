# Time Zone Semantics

`firefly` stores runtime schedule cursors as UTC `Instant` values, while each cron job owns an explicit IANA time zone.

Examples:

```java
JobDefinition job = JobDefinition.builder()
        .id("new-york-daily-report")
        .name("New York Daily Report")
        .handlerName("reportHandler")
        .schedule(new CronSchedule("0 0 9 * * *"))
        .zoneId(ZoneId.of("America/New_York"))
        .build();
```

This means `0 0 9 * * *` is interpreted as 09:00 in New York local time, not the scheduler server's default time zone.

## Rules

- Persist schedule state as UTC `Instant`.
- Evaluate cron expressions in `JobDefinition.zoneId`.
- Use IANA zone IDs such as `Asia/Shanghai`, `America/New_York`, `Europe/Berlin`.
- Do not use fixed offsets like `+08:00` for business schedules that must follow daylight saving rules.
- Never rely on `ZoneId.systemDefault()` for job semantics.

## Daylight Saving Time

The current cron engine follows Java Time zone rules through `ZonedDateTime`.

Spring forward:

- If a local time does not exist, such as `02:30` during DST spring-forward, that local occurrence is skipped.
- The next valid matching local time is used.

Fall back:

- If a local time occurs twice, such as `01:30` during DST fall-back, both instants can fire.
- They have the same local clock time but different UTC instants and offsets.

These behaviors are covered by `CronExpressionTest`.

