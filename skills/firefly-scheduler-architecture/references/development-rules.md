# Firefly Development Rules

Use this reference for architecture, module layout, storage/executor/API planning, documentation, and tests.

## Module Boundaries

Current layout:

```text
firefly
├── libs
│   └── scheduler-core
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
- Future transport modules should live under `modules`, such as `modules/api-http` or `modules/executor-http`.
- Future storage implementations should live under `modules/storage-*`.

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

## Storage Direction

Current storage is in-memory.

The in-memory repository maintains an ordered `nextFireTime` index for due-job lookup. Do not reintroduce full collection scans for normal due queries.

Future persistent storage should be introduced behind repository interfaces. Prefer:

- optimistic locking for `nextFireTime`;
- explicit version fields;
- UTC instants for cursors and execution history;
- separate task definition from runtime schedule state.

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
