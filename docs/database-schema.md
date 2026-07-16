# Firefly 数据库结构

当前 schema 版本为 `8`。以下脚本均可用于空库全量初始化，也可由 `initialize-if-empty` 模式重复执行：

```text
stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/h2.sql
stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/postgresql.sql
stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/mysql.sql
```

## 表结构

| 表 | 职责 |
|---|---|
| `firefly_schema_version` | 记录已安装的 schema 版本 |
| `firefly_cluster_metadata` | 保存所有节点必须一致的集群级不变量，例如 Scheduler 分片数 |
| `firefly_node` | Server 节点、角色、注册时间、心跳和在线状态 |
| `firefly_shard_lease` | Scheduler 分片所有权、租约和 fencing token |
| `firefly_executor` | 可持久化的逻辑执行器定义 |
| `firefly_job_group` | 任务组、默认执行器绑定、元数据和启停状态 |
| `firefly_job` | 任务定义、调度游标、分发策略和持久化 shard id |
| `firefly_execution` | 一次执行 attempt 的父记录、逻辑 root、重试 CAS、不可变 `timeout_at` 和聚合状态 |
| `firefly_execution_target` | 单播、广播或分片产生的目标子执行、ACK 和结果 |
| `firefly_dispatch_outbox` | 与任务游标同事务写入的可靠派发队列、角色路由、不可变任务快照、认领租约、ACK 超时和重试状态 |

Scheduler 不单独建表。它是 `firefly_node.roles` 中的节点职责，实际调度所有权由 `firefly_shard_lease.owner_node_id` 指向节点。

## 初始化模式

```properties
firefly.jdbc.schema.mode=initialize-if-empty
```

该模式会执行幂等建表、补齐历史版本新增列、按集群配置的分片数为旧任务回填 `shard_id`、为可恢复的旧 Outbox 回填任务快照，并为 v7 活动 execution 回填 `timeout_at`，然后验证表、列、集群不变量和 `firefly_schema_version`。生产环境由外部迁移系统管理时可以使用：

```properties
firefly.jdbc.schema.mode=validate
```

`validate` 只检查，不修改数据库。完成外部迁移后，`firefly_schema_version` 必须包含版本 `8`。

PostgreSQL 和 MySQL 初始化会先获取数据库级迁移锁，避免多个节点并发执行 DDL。PostgreSQL 使用 advisory lock，MySQL 使用 `GET_LOCK`。

Scheduler 分片数通过 `firefly.scheduler.shard-count` 配置，默认 `32`，合法范围 `1..4096`。首次由 `JdbcSchema` 初始化时会把请求值写入 `firefly_cluster_metadata.scheduler.shard-count`；后续 `initialize`、`validate` 和 `schema.mode=none` 都会校验该值。迁移锁获取后还会重新读取一次，避免两个不同配置的节点并发初始化空库时后启动者覆盖集群契约。

直接执行全量 SQL 脚本时元数据默认写入 `32`。需要自定义分片数时，应通过 `SchemaTool` 或 Server 初始化模式执行，并在所有节点上配置相同值。当前不支持在线修改分片数；修改它需要停机、重算全部 `firefly_job.shard_id` 并清理旧 lease 的显式维护流程。

## 运行状态写入

- 默认每 1 秒协调节点和续租，5 秒未更新视为离线，shard lease 默认 10 秒；三个值可配置并在启动时校验 `nodeTimeout < leaseDuration`。
- JDBC 心跳、在线窗口、shard lease 和 Outbox claim 以数据库时间为权威，避免节点时钟漂移参与所有权判断。
- shard lease 有效期 10 秒，续租或接管时使用 fencing token 防止旧节点推进任务。
- Executor 的普通 Netty 心跳保留在 Gateway 内存，不逐次写数据库。
- execution 和 target 使用事务内单向状态迁移；终态不会被迟到 ACK、重复结果或 timeout 扫描覆盖。父记录按 `ALL_SUCCESS`、`ANY_SUCCESS`、`QUORUM` 在父行锁内聚合。
- Scheduler 使用一个本地数据库事务完成 `next_fire_time` CAS、execution 和 outbox 插入。Outbox 保存本次任务的不可变快照，并以 `LOCAL` 或 `REMOTE` 限定领取角色。
- 远程 Outbox 在 ACK 前保持 `SENT`，10 秒未确认会重新派发，最多尝试 5 次；重投不会回退已 ACK 或已完成目标。PostgreSQL/MySQL 领取使用 `FOR UPDATE SKIP LOCKED`，并按角色使用复合索引。
- LOCAL Outbox 在 Handler 完成前保持 `SENT`；维护任务只由 shard 0 owner 执行。
- execution/Outbox 使用 `root_execution_id + run_attempt` 串联业务重试；`retry_scheduled` 保证同一个失败 attempt 只创建一次后继。
- attempt 在实际开始派发时用数据库时间固化 `timeout_at`，重试 backoff 不消耗运行超时；后续修改或删除任务不会改变既有 attempt 的截止时间。完成历史默认保留 30 天并批量清理。
- `ALL_SUCCESS`/`ANY_SUCCESS` 广播和分片重试会从上一 attempt 的目标记录中排除已成功目标，只重派失败、超时和缺失分片；`QUORUM` 会把上一 attempt 已成功目标复制为新 attempt 的 synthetic carry target，只重派未成功目标，并在父状态聚合时计算跨 attempt 法定多数。父状态聚合以 `expected_targets` 校验所有预期目标是否已经产生。

## 运行参数

以下参数可通过 properties、环境变量或 CLI 覆盖：

```properties
firefly.dispatch.outbox.poll-interval=PT0.2S
firefly.dispatch.outbox.claim-batch-size=50
firefly.dispatch.outbox.claim-duration=PT15S
firefly.dispatch.outbox.remote-ack-timeout=PT10S
firefly.dispatch.outbox.max-attempts=5
firefly.dispatch.outbox.max-retry-backoff=PT30S
firefly.execution.maintenance.interval=PT5S
firefly.execution.maintenance.retention=P30D
firefly.jdbc.clock.sync-interval=PT30S
firefly.jdbc.clock.drift-warning-threshold=PT1S
```

JDBC 节点会按请求往返中点把数据库时间校准到 JVM Clock。数据库不可用时保留上一次偏移并报警；首次校准失败则拒绝启动，避免使用未知时钟参与调度。
