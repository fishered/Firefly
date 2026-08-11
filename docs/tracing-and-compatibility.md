# Tracing and Rolling Upgrade Compatibility

## OpenTelemetry tracing

Enable the built-in plugin on a scheduler/gateway node:

```properties
firefly.tracing.opentelemetry.enabled=true
firefly.tracing.opentelemetry.endpoint=http://otel-collector:4318/v1/traces
firefly.tracing.opentelemetry.service-name=firefly-gateway
firefly.tracing.opentelemetry.sampling-ratio=0.25
```

The plugin configures only the OTLP SDK and exporter. Core and transport modules use the
OpenTelemetry API and remain no-op when no SDK or Java agent is present. Executor applications
can use their normal OpenTelemetry SDK/agent; the executor client automatically participates in
the same W3C `traceparent` context.

Spans are named by phase:

| Span | What it covers |
| --- | --- |
| `firefly.scheduler.schedule` | Due-job selection and schedule delay attributes |
| `firefly.outbox.claim` | Database claim query |
| `firefly.outbox.dispatch` | Outbox wait, attempt number, and dispatch acceptance |
| `firefly.gateway.dispatch` | Routing, target planning, and network write |
| `firefly.executor.execute` | Executor queue-to-handler execution |
| `firefly.result.persist` | Result mutation and retry decision in the gateway database |

The W3C carrier is stored in the immutable outbox snapshot, so a process restart does not break
the logical trace. Attributes include execution/job/node identifiers, run attempt, outbox attempt,
target counts, result status, and database operation names.

## Plugin compatibility declarations

`FireflyPlugin.runtimeCompatibility()` declares the plugin API level plus Firefly product range,
executor protocol range, database schema range, and whether the plugin is safe during a rolling
upgrade. `FireflyPluginManager` validates this before starting any plugin. Plugins compiled before
this declaration existed use the level-1 legacy default and remain binary-loadable on a level-1
host.

The checked-in matrix is [firefly-rolling-upgrade-matrix.json](../compatibility/firefly-rolling-upgrade-matrix.json).
Run:

```powershell
.\gradlew.bat verifyPluginCompatibilityMatrix
```

The matrix currently covers Firefly 1.0.4, 1.0.6, and 1.0.7 nodes, Netty protocol 1-2, JDBC schema 12,
plugin loading, protocol downgrade, and outbox trace persistence. Protocol and schema changes
must be additive until the matrix no longer has an intersection; otherwise a rolling upgrade is
rejected before plugin startup.
