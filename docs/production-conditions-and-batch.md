# Production conditions and batch execution

## Dependency-gated scheduling

`SchedulerEngine` evaluates calendar/blackout rules, then the repository's durable
condition status, then prerequisite executions in the same `scheduled_fire_time`
window. Waiting attempts are stored in `firefly_dependency_wait` and survive
restarts and scheduler failover. The prerequisite query reads `firefly_execution`
and therefore observes `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMEOUT` and `CANCELLED`.

Repositories that need a business predicate implement
`JobRepository.conditionStatus(jobId, businessTime)`. Return `WAITING` while the
predicate is not true, `BLOCKED` for a terminal negative result, and `ALLOWED`
when execution may be created. This keeps the scheduler deterministic and keeps
domain-specific data out of the execution tables.

## Batch partitioning and recovery

`BatchPartitioner` produces immutable, deterministic `BatchPartition` ranges.
`RangeBatchPartitioner` is suitable for offset/limit sources; a production
implementation can use the same contract for database key ranges or object-store
manifests. Retries must return the same boundaries for the same root execution.

Each shard result may include a checkpoint id, location and SHA-256 checksum.
`BatchRepository.saveCheckpoint` persists it with fencing, and
`latestCheckpoint` is used by a worker to resume a failed shard. Results are
idempotent by `(root_execution_id, shard_index, attempt)` and old fencing tokens
cannot overwrite a newer attempt.

Apply the additive `v15` migration for `firefly_dependency_wait` after the existing
feature migrations. The migration is loaded automatically by JDBC initialization.

Each scheduled fire time keeps its own gate. The job's normal Cron cursor is not
replaced by the gate retry clock. A gate is checked until its finite deadline;
after that it becomes `EXPIRED` and no execution is created for that fire time.
Gate claims are optimistic and fenced so two Scheduler nodes cannot release the
same gate concurrently.

## Minimal usage

Create a dependent job through Admin API with the `dependencies` field:

```json
{
  "id": "billing-settle",
  "executorName": "billing",
  "handlerName": "settle",
  "cron": "0 5 * * * *",
  "dependencies": "billing-import:10"
}
```

The value is a comma-separated list of `prerequisiteJobId:maxWaitAttempts`.
The prerequisite and dependent jobs must use the same business fire-time. A
successful prerequisite releases the dependent job; an unsuccessful prerequisite
is retried as a gate until the limit, then the fire is blocked.

For a business condition, write the state before the scheduler reaches the fire
time (or from the system that owns the condition):

```java
jobRepository.setConditionStatus(
    "billing-settle", businessTime,
    ConditionStatus.WAITING, "import is still running");
// later, when the import is complete:
jobRepository.setConditionStatus(
    "billing-settle", businessTime,
    ConditionStatus.ALLOWED, "import completed");
```

For sharded jobs, set `dispatchMode=SHARDING`, `shardCount=N`, and keep the
partition key stable. In the executor, read `param.firefly.shard.index` and
`param.firefly.shard.total` from `ExecutionContext.parameters()`, process only
that partition, and report `inputRecords`, `outputRecords`, `checkpointId`,
`checkpointLocation` and `checkpointChecksum` in `REPORT_RESULT`.
