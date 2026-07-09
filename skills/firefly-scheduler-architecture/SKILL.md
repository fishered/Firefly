---
name: firefly-scheduler-architecture
description: Project-specific guidance for Firefly, a lightweight Java 21 scheduling service. Use when designing or modifying Firefly scheduler core, cron/time-zone/DST behavior, misfire and concurrency policy, module layout, Guice wiring, tests, README/docs, or future storage/executor/API modules.
---

# Firefly Scheduler Architecture

Use this skill when working inside the Firefly project.

Firefly is a lightweight Java 21 scheduling service. Keep the core small, testable, and independent from Spring. Prefer explicit time semantics over hidden runtime defaults.

## Project Location

- Main project: `E:\workSpace\firefly`
- Default README: `README.md` in Chinese
- English README: `README_EN.md`
- Core module: `libs/scheduler-core`
- Embedded integration module: `integrations/embedded`
- Spring Boot starter module: `integrations/spring-boot-starter`
- Server CLI placeholder module: `integrations/server-cli`
- Netty executor module: `executors/netty`
- Netty Spring Boot starter module: `integrations/netty-spring-boot-starter`
- JDBC storage module: `stores/jdbc`
- Plugin API module: `plugins/plugin-api`
- Admin Web plugin: `plugins/admin-web`
- Prometheus Metrics plugin: `plugins/metrics-prometheus`
- HA cluster model: `io.github.nishi.firefly.cluster`
- Server module: `server`

## Non-Negotiables

- Do not add Spring or Spring Boot.
- Keep `libs/scheduler-core` pure Java. It must not depend on Guice, HTTP, databases, or server runtime code.
- Use Guice only in the `server` module for object wiring.
- Put traditional embedded integration code in `integrations/embedded`.
- Put Spring Boot auto-configuration only in `integrations/spring-boot-starter`; do not let Spring dependencies leak into core or traditional integration modules.
- Keep `integrations/server-cli` thin until config loading, API, and standalone process semantics are deliberately designed.
- Keep Netty transport code in `executors/netty`; use JSON protocol frames and do not put Netty dependencies in `libs/scheduler-core`.
- Keep HA coordination abstractions in core, but put JDBC/etcd/ZooKeeper implementations outside core.
- Keep JDBC storage code in `stores/jdbc`; it may implement job repository, node registry, and shard lease, but should not make scheduler tick depend on full database scans.
- Keep optional operations, pages, and metrics in `plugins/*`; do not hardcode Prometheus, HTTP pages, or plugin runtime code into `libs/scheduler-core`.
- Use shard lease and fencing token semantics for scheduler HA; do not implement HA as a single unguarded master flag.
- Use Java 21.
- Use Gradle Wrapper for normal project commands.
- Prefer small interfaces and module boundaries over framework-heavy abstractions.
- Add or update focused unit tests for scheduler behavior changes.
- Add concise Javadoc-style block comments (`/** ... */`) at key design points, especially scheduler indexing, CAS, DST policy, batching, storage consistency, and cross-module extension seams.

## Workflow

1. Read the relevant code before changing it.
2. Preserve the current multi-module layout.
3. For time, cron, DST, or schedule validation work, read `references/time-semantics.md`.
4. For module design, storage/executor/API planning, or repository layout work, read `references/development-rules.md`.
5. Implement the smallest coherent change.
6. Run tests from `E:\workSpace\firefly`.

Preferred verification:

```powershell
E:\gradle-9.6.1\bin\gradle.bat test --no-daemon
```

Wrapper command for contributors:

```powershell
.\gradlew.bat test
```

## Time Model

- Store runtime schedule cursors as UTC `Instant`.
- Require explicit IANA `ZoneId` per job, such as `Asia/Shanghai` or `America/New_York`.
- Do not use `ZoneId.systemDefault()` for job semantics.
- Do not store business schedules as fixed offsets such as `+08:00` when region rules matter.
- Cron expressions are interpreted in the job's local wall-clock time, then converted to UTC instants.

## HA Model

- Scheduler ownership is shard-based, not a single global master flag.
- A node must hold the shard lease before loading jobs for that shard into its local TimingIndex.
- Fencing tokens must be included in dispatch commands and persistent runtime-state updates.
- Business executors connect outward to scheduler gateways; business services should not be required to expose a listener port.
- The JDBC store currently provides `JdbcJobRepository`, `JdbcNodeRegistry`, `JdbcShardManager`, and `JdbcSchema`.

## DST Defaults

Current default semantics:

- Spring-forward nonexistent local times are skipped.
- Fall-back repeated local times can fire twice, once per real UTC instant.

When adding product-facing task creation or validation, prefer warnings plus next-fire previews instead of silent behavior.

## Naming

- Project name: `Firefly`
- Gradle root project: `firefly`
- Java package: `io.github.nishi.firefly`
- Avoid reintroducing `light-scheduler` or `lightscheduler`.

## Documentation

Keep documentation bilingual where useful:

- `README.md`: Chinese default for GitHub landing.
- `README_EN.md`: English version.
- `docs/timezone.md`: detailed time-zone and DST semantics.

Update docs when changing user-visible scheduling semantics.
