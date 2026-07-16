# Firefly 当前实现进度

最后更新：2026-07-16。

本文是当前实现进度的唯一入口。各主题文档只保留设计、接口和使用说明；阶段性进度统一维护在这里，避免同一件事散落到多份 Markdown 里过期。

## 1. 模块拆分

当前工程已经按轻量核心、运行时宿主、API/UI、插件、传输、持久化和示例拆分：

```text
libs/scheduler-core          调度核心，保持纯 Java
server/bootstrap             配置加载、启动参数、进程初始化
server/runtime               调度运行时装配、生命周期、Guice wiring
server/launcher              main 入口
apis/admin-model             Admin DTO / ViewModel
apis/admin-http              Admin JSON HTTP API
ui/admin                     独立 Node Admin UI 服务
plugins/plugin-api           插件 SPI
plugins/metrics-prometheus   Prometheus 指标插件
transports/netty             Netty 远程执行器协议与传输
clients/executor-netty       业务侧 executor client 边界
stores/jdbc                  JDBC 持久化与 HA 协调存储
integrations/*               Embedded、Spring Boot、Netty starter 集成
examples/*                   Embedded 与 Netty executor 示例
```

旧的 `plugins/admin-web` 和 `executors/netty` 方向已经被新边界替代：Admin HTTP API 移到 `apis/admin-http`，前端移到 `ui/admin`，Netty 协议移到 `transports/netty`。

## 2. 已落地能力

### 调度核心

- `JobDefinition` 已支持 `groupId`、任务级 `zoneId`、misfire 和 concurrency 策略。
- 已有 `CronSchedule`、`FixedRateSchedule`、`ScheduleSpec` 和 `ScheduleSpecParser`；Cron 已使用 `cron-utils` 的 Spring 5.3 六字段语义，支持 `L`、`W`、`#` 并保留 DST 双触发语义。
- Scheduler owner 已使用本地 `SchedulerTimingIndex` 和动态唤醒。数据库只轮询轻量 `jobs.revision`，任务到期后仍通过 lease + CAS + execution + Outbox 原子事务提交。
- `JobDestination` 显式区分 `LOCAL_HANDLER` 与 `REMOTE_EXECUTOR`；旧 `remote:` 前缀和 `parameters.executorName` 只作为兼容输入。
- `SchedulerCatalog` / `InMemorySchedulerCatalog` 承载任务、任务组和执行器定义。
- `JobRepository` / `InMemoryJobRepository` 承载运行态调度游标。
- `DueJobBatch` 支持 same-fire-time batch dispatch，避免 soft limit 切开同一触发时刻的任务。
- `ExecutionCommand` 已包含 `ownerNodeId` 和 `fencingToken` 字段，为 HA 防脑裂预留边界。

### HA 基础模型

- 已有 `NodeRole`、`NodeStatus`、`FireflyNode`、`NodeRegistry` 和内存实现。
- 已有 `ShardLease`、`ShardManager`、`ShardHasher` 和内存实现。
- JDBC 侧已提供 `JdbcNodeRegistry` 和 `JdbcShardManager`。
- Server runtime 已接入周期节点心跳和可配置分片协调。`firefly.scheduler.shard-count` 默认 `32`，仅在集群首次初始化时写入 `firefly_cluster_metadata`，后续所有节点必须使用相同值；不一致会在迁移前或迁移锁内再次校验并拒绝启动。
- JDBC 节点心跳、在线判定、shard lease 和 Outbox claim 统一使用数据库时间；过期后同一 `nodeId` 重获 shard 也会递增 fencing token。
- Scheduler 只把当前节点持有 shard 的任务加载进 TimingIndex，推进 `nextFireTime` 时同时校验数据库到期时间、`ownerNodeId`、`fencingToken` 和未过期 lease，快时钟节点不能提前推进任务。
- 协调周期、节点离线窗口和 lease 时长已收敛到 `SchedulerCoordinationOptions`，默认 `1s/5s/10s`，启动时强制校验 node timeout 小于 lease duration。

### 持久化

- `stores/jdbc` 已提供 H2、PostgreSQL、MySQL schema 脚本。
- `JdbcSchema` 支持通过 `DatabaseMetaData` 自动识别方言，也支持显式指定方言。
- `JdbcJobRepository` 已承载任务定义和 `nextFireTime` CAS 推进，并递增 `version`。
- `JdbcNodeRegistry` 已承载节点注册、心跳和在线状态。
- `JdbcShardManager` 已承载 shard lease 获取、续租、释放和 fencing token 递增。
- schema 已升级到 v3，增加 `firefly_job.shard_id`、`firefly_execution` 和 `firefly_execution_target`；旧任务会在迁移时按稳定哈希回填分片。
- `JdbcExecutionRepository` 已持久化逻辑执行和目标子执行，Netty `ACK_JOB`、`REPORT_RESULT` 会更新目标状态，并按 `ALL_SUCCESS`、`ANY_SUCCESS`、`QUORUM` 聚合父状态。
- schema v4 增加 `firefly_dispatch_outbox` 和 `firefly_cluster_metadata`。Scheduler 通过单库事务完成游标 CAS、execution 和 outbox 写入，不存在游标提交后尚未记录派发意图的丢任务窗口。
- schema v5 为 Outbox 增加 `dispatch_type` 和不可变 `snapshot_payload`。已入队任务不再回读可变的 `firefly_job`，任务修改或删除不会改变待派发内容。
- schema v6 增加 `firefly_job_group`，`JdbcSchedulerCatalog` 已支持执行器、任务组和 Catalog job 持久化；`firefly_cluster_metadata.jobs.revision` 用于 TimingIndex 增量刷新。
- schema v7 为 execution 和 Outbox 增加 `root_execution_id`、`run_attempt`，execution 增加 `retry_scheduled`。失败或 timeout 通过行锁 CAS 最多创建一个下一 attempt。
- schema v8 为 execution 增加不可变 `timeout_at` 和超时索引。deadline 在 attempt 实际开始派发时按数据库时间固化，任务修改、删除和 retry backoff 不再改变执行超时语义。
- execution/target 已改为事务内单向状态机；ACK、结果、父级聚合和 timeout 按父行锁串行化，迟到 ACK、冲突结果和 LOCAL 完成不能覆盖终态。
- Outbox worker 支持跨节点认领、ACK 超时重投、指数退避和最大尝试次数。`LOCAL` 记录只由 Scheduler 角色领取，`REMOTE` 记录只由 Gateway 角色领取；PostgreSQL/MySQL 使用 `FOR UPDATE SKIP LOCKED`，避免多个 worker 争抢同一候选集。
- JDBC 模式使用数据库校准 Clock，按配置周期采样数据库时间，调度、节点、Outbox、维护和 Gateway 共享同一时钟；时钟偏移、漂移告警和同步失败会进入 Prometheus。
- 广播和分片使用目标级 ACK；父 Outbox 只有在目标数达到预期且所有目标都已 ACK 后才完成。重投不会覆盖已 ACK 或已完成的目标，广播重投沿用首次实例快照。
- Executor 客户端在实例级共享 `executionId` 幂等注册表，多 Gateway 同时或重复下发时，同一进程只执行业务 Handler 一次并复用结果。
- ACK/结果上报会校验当前 Netty channel、稳定 `instanceId`、连接 `sessionId`、目标实例、owner 和 fencing token；允许同一实例通过另一条 Gateway 连接恢复上报。结果 JDBC 写入使用有界队列，高水位暂停 channel 读取、低水位恢复，不会退回 Netty EventLoop 执行阻塞 JDBC。
- `ConcurrencyPolicy.FORBID` 已在 job CAS 事务持有行锁期间检查集群活动执行，不再只依赖单 JVM 计数器。
- LOCAL Outbox 在 Handler 完成前保持 `SENT`，进程中途退出后会在任务 timeout 到期时重新投递，不再在线程池提交后提前完成。
- `ExecutionRetryPolicy` 支持总 attempt 数、初始延迟、指数倍数、最大延迟以及 failure/timeout 开关。单播按单目标 attempt 重试；`ALL_SUCCESS`/`ANY_SUCCESS` 广播和分片只重试失败、超时或缺失目标，并共享 `rootExecutionId`。`QUORUM` 会把上一 attempt 已成功目标结转为 synthetic carry target，只重派未成功目标，并按跨 attempt 成功数聚合法定多数。
- 本地与 Netty Executor 的 `ExecutionContext` 均暴露 `rootExecutionId` 和 `runAttempt`，业务 Handler 可使用稳定 root 做幂等。
- 执行维护线程只由 shard 0 owner 运行，会按任务 timeout 标记超时，并按可配置保留期批量清理完成历史；timeout 候选不再被长超时记录提前截断。
- Admin API 已改用 Jackson 解析请求，增加统一错误响应、任务启停、删除、手动触发和分级 RBAC Token；旧 API Token 兼容为 `ADMIN`，新增 `READER`、`OPERATOR`、`ADMIN` 三档权限。Prometheus 增加 outbox 与执行状态指标。
- ACK 与结果状态机可区分首次迁移、幂等重放和拒绝。重复 ACK/结果不会重复计入延迟指标，也不会重复进入业务重试调度；重投后的 ACK 延迟从本次目标投递时间计算。

### Server 启动与配置

- `server` 已拆成 `server/bootstrap`、`server/runtime`、`server/launcher`。
- `firefly.node.roles` 已成为节点职责配置入口，支持 `api`、`gateway`、`scheduler`。
- `firefly.node.mode=cluster` 已要求 JDBC 共享存储，并要求每个节点配置唯一的 `firefly.node.name`。
- `firefly.scheduler.shard-count` 支持 CLI、环境变量 `FIREFLY_SCHEDULER_SHARD_COUNT` 和 properties 配置；该值是集群级不可变契约，不提供在线静默重分片。
- `SchemaTool` 已提供显式停机式 reshard 操作：需要 `firefly.schema.action=reshard` 和 `firefly.schema.reshard.confirm=true`，并会拒绝在线节点、活跃 execution 与未完成 Outbox，随后重算 job shard、清理旧 lease、更新集群元数据。
- 配置已收敛为主配置加 profile 覆盖：

```text
config/
|-- firefly-server.properties
`-- profiles/
    |-- pg.properties
    |-- h2.properties
    `-- memory.properties
```

- 配置优先级为：CLI 参数 > 环境变量 > profile 配置 > 主配置 > 代码默认值。
- 默认 profile 为 `pg`，可切换到 `h2` 或 `memory`。
- Server 可按配置启用 Admin HTTP、Prometheus Metrics、Netty executor gateway、demo jobs 和 JDBC store。
- Outbox 轮询、认领批量、认领租约、ACK 超时、重试上限、维护批量、历史保留、数据库时钟同步和 Gateway 结果队列/帧大小均可通过运行参数配置。

### Admin API 与 UI

- Admin DTO 已放在 `apis/admin-model`。
- Admin JSON API 已放在 `apis/admin-http`，不再承载完整页面。
- 当前 JSON 接口包括：

```text
/api/health
/api/overview
/api/jobs
/api/executions
/api/executions/{executionId}
/api/outbox/dead
/api/outbox/{outboxId}/requeue
/api/executors
/api/nodes
```

- `GET /api/executions/{executionId}` 已返回父 execution、target 明细、instance/gateway、ACK/完成时间和 QUORUM carry target 标识，供 UI 与运维脚本定位跨 Gateway 重投、部分成功和结转成功目标。
- `GET /api/outbox/dead` 与 `POST /api/outbox/{outboxId}/requeue` 已支持死信查看和手动重放；重放仅允许 `DEAD -> RETRY`，会清理 claim/ACK 状态并重置 attempt，由对应角色 worker 重新领取。
- 独立 Admin UI 已放在 `ui/admin`，Node 服务默认监听 `127.0.0.1:9720`，并将浏览器侧 `/api/*` 代理到 Java Admin HTTP API。
- 当前 UI 路由包括：

```text
/
/jobs
/executors
/nodes
```

### 插件与指标

- `plugins/plugin-api` 已提供 `FireflyPlugin`、`FireflyPluginContext` 和 `FireflyPluginManager`。
- `plugins/metrics-prometheus` 已提供 Prometheus 文本指标插件。
- 当前指标除 plugin up、任务数、在线节点数和 next fire time 外，还包括调度延迟、Outbox claim age、Executor ACK 延迟、执行耗时、最老活动 Outbox age、lease 续租失败、due backlog 事件、当前持有 shard 数和数据库时钟偏移/同步失败。

### 远程执行器

- `transports/netty` 已提供 gateway、client、连接注册表、JSON codec 和消息模型。
- 执行器模型已拆为两层：`ExecutorDefinition` 是可在 Admin API/UI 手动创建、可启停、可持久化的逻辑能力；`ExecutorInstance` 是服务通过 Netty 连接注册的运行实例。
- 多个服务实例以相同的 `executorName` 注册，即共同绑定到一个逻辑执行器并参与负载分发；同一服务只有在确实提供不同能力时，才应以不同的 `executorName` 注册。
- `/api/executor-definitions` 支持定义查询与手动创建；`/api/executors` 同时返回逻辑定义和所有已观察到的运行实例，离线实例保留状态记录而不会删除定义。
- JDBC 增加 `firefly_executor` 表，用于共享执行器定义；心跳与连接状态保持在 gateway 内存注册表，断链立即标记离线，因此不会产生每次心跳写数据库的压力。
- `firefly.executor.registration.auto-create-definition` 控制 Netty 首次注册未知执行器时是否自动创建定义。独立模式默认 `true` 方便接入；集群生产环境建议设为 `false`，先由管理端创建定义再允许注册。
- 任务分发已支持 `UNICAST`、`BROADCAST`、`SHARDING`；实例路由已支持 `ROUND_ROBIN`、`RANDOM`、`CONSISTENT_HASH`。广播按在线实例快照生成子执行，分片按 `shardCount` 生成带分片参数的子执行。
- Executor 实例身份已拆为稳定的 `instanceId` 和连接级 `sessionId`。同一实例的新会话会替换旧会话，旧连接的心跳和离线事件不能覆盖新连接状态。
- Netty Executor 客户端支持同时连接多个 Gateway 地址；初始不可用或运行中断线的 Gateway 会按带抖动的指数退避重连。
- Scheduler runtime 已直接识别持久化的远程任务，不再依赖创建任务的 API 节点临时注册内存 Handler，因此多 Scheduler 节点都能读取并分发远程任务。
- 当前消息类型包括：

```text
REGISTER_EXECUTOR
REGISTERED
REGISTER_REJECTED
HEARTBEAT
TRIGGER_JOB
ACK_JOB
REPORT_RESULT
UNREGISTER_EXECUTOR
```

- `integrations/netty-spring-boot-starter` 已提供业务侧 Spring Boot 自动装配。
- Executor 注册已携带协议版本和能力集合。Gateway 会拒绝不支持的版本或缺失 `TARGET_ACK`、`RESULT_REPORT` 的客户端；客户端也会校验 Gateway 返回的协商结果。
- `ExecutorResultStore` 提供内存与文件实现。文件实现使用原子替换、版本化格式、保留期和损坏文件清理，可挂载持久卷，在 Executor 进程重启后重放已完成结果。
- `examples/netty-executor-basic` 已能模拟业务 executor 连接 server，并可自动创建远程任务。

### 示例与集成

- `integrations/embedded` 已提供传统 Java 项目嵌入式门面。
- `integrations/spring-boot-starter` 已提供 Spring Boot 自动装配。
- `integrations/server-cli` 当前保留薄 CLI 入口。
- `examples/embedded-basic` 用于验证进程内调度。
- `examples/netty-executor-basic` 用于验证远程 executor 链路。

## 3. 部分完成

- `clients/executor-netty` 已进入模块边界，但当前主要实现仍在 `transports/netty`，后续需要决定是否把业务侧 SDK 门面下沉到 client 模块。
- Admin API 当前已经具备任务启停、手动触发、执行列表、单条 execution target 明细、Outbox 死信查看和手动重放；Admin UI 仍需补齐 execution 详情页、attempt 链、死信操作入口和终止操作。
- JDBC 已具备任务、节点、执行器定义、可靠派发、执行历史和 shard lease 闭环。
- Netty 已打通长连接、会话隔离、多 Gateway 重连、协议能力协商、目标级 ACK、可插拔 Executor 结果幂等、TLS/mTLS 配置和分发路由；结果持久化使用 10,000 上限的有界队列、channel 高低水位背压和优雅排空。证书热轮换与专用 Gateway 内部转发仍未补齐。

## 4. 下一步建议

1. 为失败目标级重试增加显式策略开关，并补充 Admin UI 上的 attempt 链、目标详情和终止操作。
2. 基于现有延迟直方图建立 p99 调度延迟 SLO、告警规则和容量基线，并补充 per-shard backlog 观测。
3. 增加 Netty 证书热轮换和更细的注册拒绝原因、连接状态观测。
4. 继续扩展故障注入测试：网络分区、Gateway 宕机、长事务阻塞、数据库主从切换。
5. 评估专用 Gateway 间内部转发或统一 LB/VIP 部署模式，明确跨机房场景的推荐拓扑。

## 5. 常用验证入口

```powershell
.\gradlew.bat :server:bootstrap:test
.\gradlew.bat :stores:jdbc:test
.\gradlew.bat :transports:netty:test
.\gradlew.bat test
.\gradlew.bat :stores:jdbc:realDatabaseTest
```

运行 server：

```powershell
.\gradlew.bat :server:launcher:run
```

切换存储 profile：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=h2"
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=memory"
```
