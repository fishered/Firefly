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
- Netty long-connection remote executor foundation
- JDBC persistence for job definitions, nextFireTime CAS, node registry, heartbeats, shard leases, and fencing tokens
- Plugin SPI for optional components outside the scheduler core
- Admin HTTP API plus independent Node Admin UI for separated management APIs and operational pages
- Prometheus Metrics plugin for an independent `/metrics` text endpoint
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
│   ├── netty-spring-boot-starter
│   ├── spring-boot-starter
│   └── server-cli
├── executors
│   └── netty              # remote executor long-connection transport
├── stores
│   └── jdbc               # JDBC job repository and HA coordination storage
├── plugins
│   ├── plugin-api         # plugin SPI and lifecycle management
│   └── metrics-prometheus # Prometheus text metrics plugin
├── docs
│   ├── deployment.md      # image build and container deployment
│   ├── integration.md     # integration guide
│   ├── ha-cluster.md
│   ├── netty-executor.md
│   ├── plugins.md
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
transports/http
apis/admin-http
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
./gradlew :server:launcher:run
```

Windows:

```powershell
.\gradlew.bat :server:launcher:run
```

When started from the project root, the server automatically loads `config/firefly-server.properties`. The current default profile is `pg`, which enables Admin HTTP, Prometheus Metrics, the Netty executor gateway, and local PostgreSQL persistence; the demo job is still disabled by default.

Node duties are configured with `firefly.node.roles`. The default config runs a single process with all server roles:

```properties
firefly.node.mode=standalone
firefly.node.name=firefly-standalone
firefly.node.roles=api,gateway,scheduler
```

`cluster` mode requires JDBC shared storage, and each node must use a unique `firefly.node.name`.

Enable the 5-second demo job:

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.demo.enabled=true"
```

Switch to local H2 file storage:

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=h2"
```

Switch to in-memory storage:

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=memory"
```

The main config file is `config/firefly-server.properties`, and profile-specific values live in `config/profiles/*.properties`. Command-line flags and environment variables override file values.

## Integration

- Traditional Java services: use `integrations:embedded` and embed Firefly through `FireflyScheduler.create()`.
- Spring Boot services: use `integrations:spring-boot-starter` and declare `FireflyJobRegistration` beans.
- Remote business executors: use `transports:netty`; business services actively connect to the scheduler gateway and do not need to expose listener ports.
- Standalone server: `integrations:server-cli` keeps a command entry point for future config loading and process mode.

See [docs/integration.md](docs/integration.md).

## Scheduler Center Model

Job groups, executors, service instance registration, heartbeat liveness, persistence boundaries, and remote trigger protocols are described in [docs/scheduler-center.md](docs/scheduler-center.md).

Netty remote executor integration is described in [docs/netty-executor.md](docs/netty-executor.md).

HA node roles, shard leases, fencing tokens, and JDBC storage are described in [docs/ha-cluster.md](docs/ha-cluster.md).

JDBC store and schema dialect scripts are described in [docs/jdbc-store.md](docs/jdbc-store.md).

Plugin SPI, Admin HTTP, and Prometheus Metrics are described in [docs/plugins.md](docs/plugins.md). These plugins are not loaded by the server by default and must be enabled explicitly.

Module boundaries and the executor/server split are described in [docs/module-boundaries.md](docs/module-boundaries.md).

Image build and container node role configuration are described in [docs/deployment.md](docs/deployment.md).

Runnable examples are described in [docs/examples.md](docs/examples.md).

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
3. Execution history and token-aware runtime state.
4. Execution history and state transitions.
5. Remote executor authentication, TLS, and routing policies.
6. Scheduler shard loading and local TimingIndex integration.
7. Tracing and richer plugin discovery.

## Name

Firefly means "萤火虫".

It is small, quiet, and able to light up at the right moment. As scheduler nodes spread across machines, regions, and services, each node can become a small point of light in a reliable task network.

## Admin API and UI Convention

Admin HTTP is an optional management API, and Admin UI is an independent Node service. Frontend resources must not be placed in scheduler core or embedded in the Admin HTTP jar.

- Java entry point: apis/admin-http/src/main/java/com/firefly/api/admin/http/AdminHttpPlugin.java
- Node UI: ui/admin
- Page routes: /, /jobs, /executors, /nodes
- JSON endpoints: /api/health, /api/overview, /api/jobs, /api/executors, /api/nodes

Start Firefly server first, then run `npm start` in `ui/admin`. The Node UI listens on `127.0.0.1:9720` by default and proxies `/api/*` to `FIREFLY_ADMIN_API`, defaulting to `http://127.0.0.1:9710`.

Do not embed full HTML pages in Java text blocks. New management endpoints go into `apis/admin-http`; frontend pages and the Node service go into `ui/admin`.

## Target Module Boundaries

Firefly is moving toward separate runtime, API, UI, transport, and client modules:

`	ext
libs/scheduler-core        pure Java scheduling core
server                     runtime wiring, startup, lifecycle
apis/admin-model          Admin DTOs and view models
apis/admin-http           Admin HTTP APIs
ui/admin                  Node-based Admin UI
plugins/plugin-api        plugin SPI
plugins/metrics-prometheus Prometheus metrics plugin
transports/netty          Netty protocol and transport
clients/executor-netty    business-side executor SDK
`

Admin UI should evolve as an independent Node frontend, while Admin APIs belong to the server/API layer. `apis/admin-http` and `ui/admin` are long-term separate API/UI modules.
