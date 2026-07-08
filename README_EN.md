# Firefly

Firefly is a lightweight Java 21 scheduling service.

Like fireflies in the dark, it aims to provide small but reliable points of light: precise triggers, explicit time semantics, and a scheduler core that can grow naturally from a single process into a distributed system.

## Positioning

Firefly focuses on three goals:

- **Correct time semantics**: jobs declare an explicit IANA `ZoneId`, cron schedules are evaluated in the job's local time, and runtime cursors are stored as UTC `Instant` values.
- **Lightweight core**: `scheduler-core` stays pure Java and does not depend on Spring, Guice, HTTP, databases, or a specific runtime.
- **Modular growth**: storage, executor protocols, management APIs, and clustering can evolve as separate modules.

## Features

- Java 21
- Gradle multi-module build
- Gradle Wrapper for reproducible builds
- pure Java scheduler core in `libs/scheduler-core`
- Guice wiring in the `server` module
- embedded integration facade for traditional Java services
- Spring Boot Starter auto-configuration entry point
- Server CLI placeholder module
- in-memory job repository
- job-level IANA time zone support
- 6-field cron: second minute hour day month weekday
- fixed-rate schedules
- misfire policies: `SKIP`, `FIRE_ONCE`, `CATCH_UP`
- concurrency policies: `ALLOW`, `FORBID`
- local job handler registry
- unit tests for cron, time zones, DST, repository CAS, misfire behavior, and Guice wiring

## Project Layout

```text
firefly
├── libs
│   └── scheduler-core     # pure scheduling core, no Guice/Spring
├── server                 # app entry point and Guice wiring
├── integrations
│   ├── embedded           # traditional Java / non-Spring integration
│   ├── spring-boot-starter
│   └── server-cli
├── docs
│   ├── integration.md     # integration guide
│   ├── scheduler-center.md
│   └── timezone.md        # time zone and DST semantics
├── skills                 # project-specific collaboration rules
├── gradle/wrapper
├── build.gradle
├── settings.gradle
├── gradlew
└── gradlew.bat
```

Recommended extension layout:

```text
stores/jdbc
executors/http
apis/http
plugins/xxx
```

## Quick Start

Requirements:

- JDK 21

Run tests:

```bash
./gradlew test
```

Windows:

```powershell
.\gradlew.bat test
```

Run the demo server:

```bash
./gradlew :server:run
```

Windows:

```powershell
.\gradlew.bat :server:run
```

The demo registers a local handler and schedules a sample cron job every 5 seconds.

## Integration

- Traditional Java services: use `integrations:embedded` and embed Firefly through `FireflyScheduler.create()`.
- Spring Boot services: use `integrations:spring-boot-starter` and declare `FireflyJobRegistration` beans.
- Standalone server: `integrations:server-cli` keeps a command entry point for future config loading and process mode.

See [docs/integration.md](docs/integration.md).

## Scheduler Center Model

Job groups, executors, service instance registration, heartbeat liveness, persistence boundaries, and remote trigger protocols are described in [docs/scheduler-center.md](docs/scheduler-center.md).

## Example Job

```java
JobDefinition job = JobDefinition.builder()
        .id("demo-print-every-5s")
        .name("Demo print every five seconds")
        .handlerName("demoPrinter")
        .schedule(new CronSchedule("*/5 * * * * *"))
        .zoneId(ZoneId.of("Asia/Shanghai"))
        .misfirePolicy(MisfirePolicy.FIRE_ONCE)
        .misfireGrace(Duration.ofSeconds(2))
        .concurrencyPolicy(ConcurrencyPolicy.FORBID)
        .maxCatchUpCount(3)
        .timeout(Duration.ofSeconds(10))
        .enabled(true)
        .build();
```

## Time Zone Semantics

Every cron job has its own explicit `ZoneId`.

```java
JobDefinition job = JobDefinition.builder()
        .id("new-york-daily-report")
        .name("New York Daily Report")
        .handlerName("reportHandler")
        .schedule(new CronSchedule("0 0 9 * * *"))
        .zoneId(ZoneId.of("America/New_York"))
        .build();
```

This means `0 0 9 * * *` runs at 09:00 in New York local time. It does not depend on the scheduler server's default time zone.

Firefly's time rules:

- Persist runtime schedule cursors as UTC `Instant`.
- Evaluate cron expressions in `JobDefinition.zoneId`.
- Use IANA zone IDs such as `Asia/Shanghai`, `America/New_York`, `Europe/Berlin`.
- Avoid fixed offsets such as `+08:00` for business schedules that must follow daylight saving rules.
- Do not use `ZoneId.systemDefault()` for job semantics.

DST behavior:

- Spring-forward nonexistent local times are skipped.
- Fall-back repeated local times can fire twice, once per real UTC instant.

See [docs/timezone.md](docs/timezone.md).

## Design Principles

- **Explicit by default**: time zones, misfire policies, and concurrency policies should be visible in job definitions.
- **Small core**: the scheduler core owns scheduling semantics only; Web, storage, remote calls, and IOC containers stay outside the core.
- **Modular evolution**: new capabilities should live in focused modules connected through small interfaces.
- **Testable behavior**: time calculation, repository consistency, and execution policies should be easy to verify with unit tests.

## Roadmap

1. Lightweight HTTP management API.
2. JSON/YAML job definition loading.
3. JDBC repository module.
4. Execution history and state transitions.
5. Remote executor protocol.
6. Scheduler shard and lease mechanism.
7. Metrics and tracing.

## Name

Firefly means "萤火虫".

It is small, quiet, and able to light up at the right moment. As scheduler nodes spread across machines, regions, and services, each node can become a small point of light in a reliable task network.
