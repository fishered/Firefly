# Firefly module boundaries

The repository is split by bounded context. `libs/scheduler-core` is retained as
a compatibility/runtime facade so existing applications can upgrade without
changing imports in one release.

```text
firefly/                         # Gradle root: parent conventions and publishing
├── libs/                        # Gradle aggregator for core leaf modules
│   ├── firefly-domain            # published leaf module
│   ├── firefly-scheduling        # published leaf module
│   ├── firefly-execution         # published leaf module
│   ├── firefly-batch             # published leaf module
│   ├── firefly-store-api         # published leaf module
│   ├── firefly-engine            # published leaf module
│   └── scheduler-core            # published compatibility leaf module
├── stores/                      # aggregator -> JDBC leaf
├── transports/                  # aggregator -> Netty protocol/transport leaves
├── server/                      # aggregator -> runtime/bootstrap/launcher leaves
├── integrations/               # aggregator -> Starter/Embedded/Remote leaves
└── plugins/                    # aggregator -> SPI/observability leaves

support/                        # Non-runtime benchmarks and repository skills

firefly-domain -> firefly-batch, firefly-execution, firefly-scheduling,
                  firefly-catalog, firefly-store-api
firefly-store-api -> stores-jdbc
firefly-execution -> firefly-executor
firefly-engine -> scheduling / execution / store-api / cluster / executor / handler
firefly-runtime-support -> in-memory stores / audit / metrics / lifecycle
firefly-engine -> server/runtime -> spring-boot integrations
```

`firefly-domain` owns immutable job, schedule, executor and execution context models.
`firefly-scheduling` owns Cron, calendar, blackout and dependency semantics.
`firefly-execution` owns execution lifecycle, target aggregation, retry, fencing and trace carriers.
`firefly-batch` owns partitioning, progress, checkpoint and object-store contracts.
`firefly-catalog` owns scheduler configuration catalog contracts.
`firefly-store-api` contains persistence contracts and storage records only; JDBC contains SQL and migrations.
`firefly-trigger` owns event inbox and bounded backfill; `firefly-handler` owns handler registration/idempotency;
`firefly-operations` owns timeline and alert evaluation; `firefly-cluster`, `firefly-security`, and
`firefly-executor` own coordination, identity, and transport-neutral executor contracts.
`firefly-engine` owns orchestration only. In-memory stores, audit, metrics and lifecycle helpers live in
`firefly-runtime-support`; JDBC remains the production persistence implementation. Transport, Admin and
Starter consume the contracts through engine/runtime and never depend on each other.

The root `build.gradle` is the Gradle equivalent of a parent POM: it owns the
Java toolchain, encoding, test defaults, version, publishing conventions and
the published module list. Directories such as `libs`, `server`, and `stores`
are aggregators; their leaf projects produce the functional artifacts.

The migration is intentionally incremental: package names remain source-compatible while physical
implementations move behind the new boundaries. New integrations should depend on the smallest module
that exposes the required contract; `scheduler-core` is for legacy compatibility and the scheduler engine.
