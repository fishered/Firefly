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
- Netty executor module: `transports/netty`
- Netty Spring Boot starter module: `integrations/netty-spring-boot-starter`
- JDBC storage module: `stores/jdbc`
- Plugin API module: `plugins/plugin-api`
- Admin HTTP/API and Admin UI: `apis/admin-http` and `ui/admin`
- Prometheus Metrics plugin: `plugins/metrics-prometheus`
- Embedded example module: `examples/embedded-basic`
- HA cluster model: `com.firefly.cluster`
- Server module: `server`

## Non-Negotiables

- Do not add Spring or Spring Boot.
- Keep `libs/scheduler-core` pure Java. It must not depend on Guice, HTTP, databases, or server runtime code.
- Use Guice only in the `server` module for object wiring.
- Put traditional embedded integration code in `integrations/embedded`.
- Put Spring Boot auto-configuration only in `integrations/spring-boot-starter`; do not let Spring dependencies leak into core or traditional integration modules.
- Keep `integrations/server-cli` thin until config loading, API, and standalone process semantics are deliberately designed.
- Keep Netty transport code in `transports/netty`; use JSON protocol frames and do not put Netty dependencies in `libs/scheduler-core`.
- Keep HA coordination abstractions in core, but put JDBC/etcd/ZooKeeper implementations outside core.
- Keep JDBC storage code in `stores/jdbc`; it may implement job repository, node registry, and shard lease, but should not make scheduler tick depend on full database scans.
- Keep JDBC schema SQL in dialect resource scripts; do not hardcode database-specific DDL in Java code.
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
- Java package: `com.firefly`
- Avoid reintroducing `light-scheduler` or `lightscheduler`.

## Documentation

Keep documentation bilingual where useful:

- `README.md`: Chinese default for GitHub landing.
- `README_EN.md`: English version.
- `docs/timezone.md`: detailed time-zone and DST semantics.

Update docs when changing user-visible scheduling semantics.

## Admin HTTP And UI Rules

- Keep Admin HTTP and Admin UI optional and isolated in `apis/admin-http` and `ui/admin`. Do not put web pages, HTTP handlers, or frontend dependencies into libs/scheduler-core.
- Keep Node Admin UI source, static assets, and its local Node service in `ui/admin`. Do not embed full HTML pages in Java text blocks.
- Keep Java code responsible for plugin lifecycle and JSON APIs. Keep page markup, CSS, browser JavaScript, and Node service code in `ui/admin`.
- Treat the Admin UI as an independent Node service that proxies `/api/*` to `apis/admin-http`. Do not package the full UI into `apis/admin-http` unless a separate distribution mode is explicitly requested.
- Update README.md, README_EN.md, and docs/plugins.md whenever Admin HTTP routes, APIs, packaging, or activation behavior changes.

## Server Configuration Rules

- Keep the default server config at `config/firefly-server.properties`; it should contain common runtime settings and a `firefly.config.profile` selector.
- Put environment or storage-specific settings in `config/profiles/*.properties`. Do not create duplicate full server config files for pg/h2/memory variants.
- Keep precedence as CLI flags > environment variables > profile config > main config > code defaults.
- Use `--firefly.config.profile=<name>` or `FIREFLY_CONFIG_PROFILE` to switch profiles. Use `firefly.config.profile=none` only when intentionally disabling profile loading.

## Target Module Boundaries

- Keep libs/scheduler-core as the pure scheduling domain core.
- Treat server as runtime wiring, bootstrap, and lifecycle. Do not turn it into a mixed UI/API/plugin blob.
- Put Admin DTOs and ViewModels in apis/admin-model.
- Put management HTTP endpoints in apis/admin-http.
- Put Node-based Admin UI source or build outputs under ui/admin.
- Keep plugins for optional runtime extensions such as metrics-prometheus, not for the long-term Admin UI shape.
- Split executor-related code by responsibility: transports/netty for protocol and transport, clients/executor-netty for business-side SDK, and server-side gateway wiring in the server/runtime layer.
- Treat `apis/admin-http` and `ui/admin` as separate API/UI modules. New Admin API work should go to apis/admin-http, and UI work should go to ui/admin.
