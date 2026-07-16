# 调度中心模型

本文整理 Firefly 调度中心的核心模型：任务持久化、任务组、执行器、服务在线状态、调度配置和远程触发协议。

## 1. 持久化边界

调度中心需要区分两类数据：

- **配置数据**：任务、任务组、执行器定义、服务注册信息。
- **运行态数据**：下一次触发时间、执行状态、心跳租约、执行日志。

当前代码中对应两条接口线：

- `SchedulerCatalog`：保存配置数据，例如 `ExecutorDefinition`、`JobGroupDefinition`、`JobDefinition`。
- `JobRepository`：保存运行态调度游标，例如 `ScheduledJobRecord.nextFireTime`。

后续 JDBC 存储不要把这两类数据混在一张表里。推荐表结构方向：

```text
executor_definition     # 逻辑执行器
executor_instance       # 服务实例注册和心跳
job_group_definition    # 任务组，绑定一个逻辑执行器
job_definition          # 任务配置
job_runtime_state       # next_fire_time、version、lease
job_execution_log       # 执行历史
```

所有运行态时间继续使用 UTC `Instant`。业务时间，例如每天 01:00，必须和任务的 IANA `ZoneId` 一起解释。

持久化存储是事实来源，但不是调度热路径。调度节点启动或接收到配置事件后，把 active jobs 加载进本地时间索引；每次 tick 从本地索引取 due jobs，而不是每 500ms 扫数据库。

推荐热路径：

```text
load active jobs from store
        ↓
build local TimingIndex
        ↓
tick reads local due batch
        ↓
advance local runtime cursor
        ↓
dispatch execution command
        ↓
checkpoint runtime state asynchronously
```

当前实现已经使用 owner 本地 `SchedulerTimingIndex`：配置变更递增共享 `jobs.revision`，Scheduler 在 revision 或 shard ownership 变化时重载索引，并按最近 `nextFireTime` 动态唤醒。真正触发仍同步提交数据库 CAS + execution + Outbox 事务，因此数据库不可用时不会继续产生无法 fencing 的新执行。

## 2. 任务组与执行器

Firefly 采用三层关系，并用结构化 `JobDestination` 表达本地或远程目标：

```text
JobDefinition -> JobGroupDefinition -> ExecutorDefinition -> ExecutorInstance
```

- `JobDefinition.groupId`：任务属于哪个任务组。
- `JobGroupDefinition.executorName`：任务组绑定到哪个逻辑执行器。
- `ExecutorDefinition.name`：逻辑执行器名称。
- `ExecutorInstance`：真实在线服务实例。

一个任务组可以包含多个任务。一个逻辑执行器可以有多个服务实例，这样同一组任务可以路由到多个可用实例。

一个服务实例是否应该注册到多个执行器？

- 默认不建议。一个服务注册到一个执行器，语义最清楚。
- 如果同一个服务确实暴露多套能力，例如既能处理 `billing-executor`，也能处理 `report-executor`，可以注册多条 `ExecutorInstance` 记录。
- 多执行器注册应表示“多套能力”，不应该只是为了负载均衡。负载均衡应该发生在同一个执行器的多个实例之间。

## 3. 调度配置

Firefly 当前支持三类可直接执行的调度描述：

- `CRON`：cron 表达式，例如 `0 0 1 * * *`。
- `FIXED_RATE`：固定周期，例如 `PT1M`。
- `DAILY_TIME`：每天固定本地时间，例如 `01:00`，解析为任务时区下的每日 cron。

同时保留两类需要更完整运行态支持的调度描述：

- `MANUAL`：手动触发，不产生自动 next-fire time。
- `LINEAR_BACKOFF`：例如首次等待 1 分钟，每次多等待 1 分钟。

`LINEAR_BACKOFF` 不能只靠一个无状态 `Schedule.nextAfter(Instant, ZoneId)` 正确表达。它需要持久化执行次数或当前 delay，例如：

```text
job_runtime_state
├── fire_count
├── last_fire_time
├── current_delay
└── next_fire_time
```

所以当前实现先允许保存 `ScheduleSpec.linearBackoff(...)`，但不会把它解析成可执行 `Schedule`。等 `job_runtime_state` 增加状态字段后，再接入执行。

## 3.1 same-fire-time batch dispatch

多个任务配置在同一个触发时刻时，Firefly 使用 **same-fire-time batch dispatch** 语义：

```text
same nextFireTime -> same DueJobBatch -> same scheduledFireTime
```

`JobRepository.findDueBatch(now, softLimit, hardLimit)` 会先找到最早 due 的 `nextFireTime`，然后尽量返回所有相同 `nextFireTime` 的任务。`softLimit` 不会切开同一时刻的任务，`hardLimit` 只作为内存和延迟保护。

调度中心保证的是调度语义一致：

- 同一批任务拥有相同 `scheduledFireTime`。
- 调度线程只推进状态并生成 `ExecutionCommand`。
- 任务执行交给 dispatcher / executor，不阻塞 tick 线程。
- `dispatchTime - scheduledFireTime` 可用于观测调度延迟。

系统不能承诺所有任务在物理同一毫秒开始执行。JVM、OS、线程池、网络和下游 executor 都会造成抖动。Firefly 要做的是让计划时间一致、分发尽量同批、延迟可观测。

实现上，内存 repository 的锁只保护本地 map 和 `nextFireTime` 索引。锁内只做快照读取或索引更新，不执行任务、不做网络调用、不等待下游服务。调度线程拿到 due batch 后释放锁，再推进状态和分发命令。

如果 1000 个任务的 fire time 都不同，调度线程不会只处理固定 10 组就停。当前策略是按 tick 记录数预算推进：

```text
MAX_DUE_RECORDS_PER_TICK = 10000
MAX_DUE_FIRE_TIME_GROUPS_PER_TICK = 10000
```

同一 fire time 的任务不会被 soft limit 拆开；不同 fire time 的任务会在同一 tick 内继续 drain，直到没有 due job 或达到预算上限。

## 4. 服务在线检查

### 执行重试

业务重试使用稳定的 `rootExecutionId` 和递增 `runAttempt`。`maxAttempts` 包含初始执行；失败和 timeout 可分别启停，并支持有上限的指数退避。当前广播和分片会重试整个逻辑运行，不只重试失败目标，因此业务 Handler 应以 `rootExecutionId` 实现幂等。

执行器实例在线状态通过 `ExecutorRegistry` 表达：

- 服务启动时注册 `ExecutorInstance`。
- 服务周期性发送 heartbeat。
- 调度中心按 heartbeat timeout 判断实例是否在线。
- 实例主动下线时调用 mark offline。

这套模型可以被不同实现承载：

- 内存实现：本地测试和嵌入式模式。
- JDBC 实现：持久化注册表和 lease。
- TCP/Netty 实现：长连接心跳。
- HTTP 实现：简单但请求开销更高。

调度时不应该盲目向某个服务发任务，而是先通过执行器名找到在线实例，再按路由策略选择目标实例。

## 5. Netty 与通讯协议

Netty 不是业务协议，它是 TCP 通讯实现。Firefly 不应该把 Netty 放进 core。

更合适的分层是：

```text
core
└── ExecutorRegistry / ExecutorDispatcher 接口

transports/netty
└── 基于 Netty 的长连接、JSON 消息、心跳、任务触发、ack

transports/http
└── 基于 HTTP 的任务触发
```

Netty 适合做：

- 服务实例启动后建立长连接。
- 周期性心跳。
- 调度中心推送触发命令。
- 执行器回传 accepted / running / succeeded / failed。
- 连接断开后快速标记实例不可用。

协议层建议保持小而明确：

```text
REGISTER_EXECUTOR
HEARTBEAT
TRIGGER_JOB
ACK_JOB
REPORT_RESULT
UNREGISTER_EXECUTOR
```

这样调度中心像监听者，但不是被动观察者：它维护在线实例视图，在任务到期时选择合适实例发送触发命令。

## 6. 实现进度

当前实现进度统一维护在 [implementation-progress.md](implementation-progress.md)。
