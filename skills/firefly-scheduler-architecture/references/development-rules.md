# Firefly Development Rules

Use this reference for architecture, module layout, storage/executor/API planning, documentation, and tests.

## Module Boundaries

Current layout:

```text
firefly
├── libs
│   └── scheduler-core
├── integrations
│   ├── embedded
│   ├── netty-spring-boot-starter
│   ├── spring-boot-starter
│   └── server-cli
├── executors
│   └── netty
├── stores
│   └── jdbc
├── plugins
│   ├── plugin-api
│   ├── admin-web
│   └── metrics-prometheus
├── server
├── docs
├── skills
├── gradle/wrapper
├── README.md
└── README_EN.md
```

Rules:

- `libs/scheduler-core` owns domain models, schedule calculation, repositories interfaces, dispatch policy, and core tests.
- `server` owns app startup and Guice wiring.
- Transport implementations should live under specific capability roots, such as `executors/netty`, `executors/http`, or `apis/http`.
- Storage implementations should live under `stores/*`; `stores/jdbc` currently owns JDBC job repository, node registry, and shard lease coordination.
- Optional operational components should live under `plugins/*`; `plugins/plugin-api` owns the SPI, while concrete plugins own their transport and presentation dependencies.
- Keep executor registration and heartbeat abstractions in core, but put Netty/HTTP implementations outside core.

## Dependency Rules

Allowed in core:

- Java standard library;
- small libraries only when they clearly reduce risk;
- JUnit for tests.

Avoid in core:

- Spring;
- Guice;
- HTTP server frameworks;
- database clients;
- executor network protocol clients.
- metrics or admin-page implementations.

## Storage Direction

Current job repository storage has in-memory and JDBC implementations. JDBC HA coordination storage exists for node registry and shard lease.

The in-memory repository maintains an ordered `nextFireTime` index for due-job lookup. Do not reintroduce full collection scans for normal due queries.

Persistent job/runtime storage should stay behind repository interfaces. Prefer:

- optimistic locking for `nextFireTime`;
- explicit version fields;
- UTC instants for cursors and execution history;
- separate task definition from runtime schedule state;
- separate executor definitions, executor instances, job groups, job definitions, runtime state, and execution logs.
- Do not put persistent storage on the scheduler tick hot path. Scheduler nodes should use a local timing index and checkpoint runtime state asynchronously or through bounded write paths.
- Do not split jobs with the same `nextFireTime` by normal soft batch limits; use same-fire-time batch dispatch with a separate hard safety limit.
- Use shard lease and fencing token checks before a scheduler node advances runtime state.

## Distributed Scheduling Direction

Avoid centralizing all due-job scans behind one global lock.

Future cluster design should consider:

- shard ownership;
- lease-based scheduler nodes;
- consistent hashing by tenant/job id;
- failover on lease expiration;
- delayed scheduling metrics;
- idempotent execution ids.

## Executor Direction

Keep execution protocol replaceable.

Future executor modules may include:

- local handler registry;
- HTTP executor;
- gRPC executor;
- signed callback;
- heartbeat and health checks.

## Testing Rules

Every scheduler behavior change should include focused tests.

Prefer tests around:

- cron next-fire calculation;
- timezone conversion;
- DST gap/overlap;
- misfire policy;
- concurrency policy;
- repository compare-and-set behavior;
- Guice wiring only in `server`.

Run:

```powershell
E:\gradle-9.6.1\bin\gradle.bat test --no-daemon
```

## Code Comment Rules

Use concise Javadoc-style block comments for non-obvious design points:

```java
/**
 * Explain why this structure or policy exists.
 */
```

Prefer comments around:

- scheduler indexes and due-job lookup;
- compare-and-set state transitions;
- DST gap/overlap policy choices;
- batching, limits, and backpressure;
- module extension points;
- storage consistency assumptions.

Avoid noisy comments that merely repeat the code.

## Documentation Rules

Update docs when behavior changes:

- `README.md` for user-facing Chinese summary;
- `README_EN.md` for English summary;
- `docs/timezone.md` for time semantics;
- `docs/design.md` for architecture;
- `docs/roadmap.md` for staged work.

Do not let code and docs disagree on DST semantics.
