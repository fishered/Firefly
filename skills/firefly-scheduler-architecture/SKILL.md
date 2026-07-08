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
- Server module: `server`

## Non-Negotiables

- Do not add Spring or Spring Boot.
- Keep `libs/scheduler-core` pure Java. It must not depend on Guice, HTTP, databases, or server runtime code.
- Use Guice only in the `server` module for object wiring.
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
