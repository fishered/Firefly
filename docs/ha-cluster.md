# HA Cluster Design

Firefly 的 HA 不是简单启动多个节点。调度系统必须解决两个问题：

- 多个 scheduler 节点不能重复触发同一个任务。
- 多个业务 executor 实例要共享同一个逻辑执行器能力。

## 节点角色

Firefly 节点可以拥有多个角色：

```text
SCHEDULER   负责持有 shard lease、扫描本地 TimingIndex、生成 ExecutionCommand
GATEWAY     维护 executor Netty 长连接
API         暴露管理 API 和配置写入入口
```

`STANDBY` 不是配置角色，而是 `SCHEDULER` 节点没拿到 shard lease 时的运行态。

单机模式：

```text
node-1: SCHEDULER + GATEWAY + API
```

第一阶段推荐的最小 HA 集群是三个节点都启用全角色：

```text
node-1: SCHEDULER + GATEWAY + API
node-2: SCHEDULER + GATEWAY + API
node-3: SCHEDULER + GATEWAY + API
```

配置示例：

```properties
firefly.node.mode=cluster
firefly.node.name=firefly-node-1
firefly.node.roles=api,gateway,scheduler
firefly.store.type=jdbc
firefly.scheduler.coordination.reconcile-interval=PT1S
firefly.scheduler.coordination.node-timeout=PT5S
firefly.scheduler.coordination.lease-duration=PT10S
```

默认故障窗口满足 `node timeout < shard lease`：失效节点会先从在线成员集合移除，其他节点在旧 lease 到期后接管；fencing token 负责拒绝旧 owner 的后续写入。配置加载时会拒绝不满足该约束的组合。

后续也可以拆成更细的部署：

```text
scheduler nodes
gateway nodes
api nodes
business executor nodes
```

## 主节点故障感知

子服务不直接判断“谁是主”。主从切换由 scheduler 节点之间的 shard lease 完成。

```text
scheduler node heartbeat
        ↓
shard lease renewal
        ↓
lease expired
        ↓
standby node acquires shard
        ↓
new owner loads jobs and resumes scheduling
```

业务 executor 只负责连接 gateway。如果当前连接断开，executor client 重连其他 gateway。

## Shard Lease

任务通过稳定 hash 映射到 shard：

```text
jobId -> ShardHasher -> shardId
```

分片总数由 `firefly.scheduler.shard-count` 指定，默认 `32`。它会在集群首次初始化时写入共享元数据，之后所有节点必须保持一致；启动时会在迁移前和迁移锁内校验，避免并发首启覆盖。当前不做在线自动重分片。

每个 shard 同一时刻只能被一个 scheduler node 持有：

```text
ShardLease
├── shardId
├── ownerNodeId
├── leaseUntil
└── fencingToken
```

节点拿到 shard lease 后，才能加载该 shard 的任务到本地 TimingIndex 并触发任务。

JDBC 实现位于 `stores/jdbc`：

```text
JdbcJobRepository  持久化任务定义和 nextFireTime，并用 CAS 推进调度游标
JdbcNodeRegistry    持久化节点注册、心跳、在线状态
JdbcShardManager    持久化 shard lease，并用事务 + select for update 控制抢占
JdbcSchema          创建任务仓库和 HA 协调表
```

`JdbcShardManager.acquire(...)` 的语义：

- shard 不存在时创建 lease，`fencingToken = 1`。
- 当前 owner 续拿 shard 时保留原 token。
- 其他节点只能在 lease 过期或被释放后接管，接管时 token 递增。
- 其他节点在 lease 未过期时抢占会返回 empty，不会触发任务。

## Fencing Token

fencing token 用来防脑裂。

当 shard 被新节点接管时，token 递增：

```text
node-1 owns shard-1, token=1
node-1 expires
node-2 owns shard-1, token=2
```

旧 node-1 即使恢复，也只能拿着 token=1。它推进 runtime state 或发出的 execution command 都应该被拒绝。

`ExecutionCommand` 已包含：

```text
ownerNodeId
fencingToken
```

当前 JDBC 游标推进已经在同一条 CAS 中校验到期时间、shard owner、fencing token 和数据库时间下未过期的 lease。JVM 时钟即使偏快，也不能在数据库 `next_fire_time` 之前推进游标。

## 避免重复触发

Firefly 使用多层保护：

```text
shard owner            正常情况下只有 owner 扫描任务
CAS nextFireTime       推进 runtime state 时校验 expected nextFireTime/version/token
executionId            jobId + scheduledFireTime 幂等
execution log unique   防止重复记录
executor idempotency   业务侧最终兜底
```

Netty 客户端可使用 `FileExecutorResultStore` 将已完成目标结果保存到持久卷，同一 `executionId` 在进程重启后可直接重放结果。协议 v1 同时协商 `TARGET_ACK`、`RESULT_REPORT` 等能力，协商不完整时双方拒绝继续使用连接。

不要承诺绝对 exactly-once。更现实的目标是 effectively-once：

```text
尽量只触发一次 + 存储防重复 + 执行侧幂等
```

文件结果存储不能覆盖“业务副作用已经发生，但结果尚未持久化时进程崩溃”的窗口，也不能替代跨实例业务幂等。因此 Firefly 的语义仍是 at-least-once 传递基础上的 effectively-once，而不是绝对 exactly-once。

## 业务服务 HA

多个业务实例注册到同一个 executor：

```text
billing-service-1 -> billing-executor
billing-service-2 -> billing-executor
billing-service-3 -> billing-executor
```

调度时：

```text
job -> group -> executor -> online executor instance
```

如果某个 executor instance 心跳超时，router 不再选择它。

如果所有实例都不可用，任务进入 dispatch failure / misfire 策略，而不是假装执行成功。广播和分片的下一次 attempt 只重试失败、超时或缺失目标，不会再次执行已经成功的目标。

## 实现进度

当前实现进度统一维护在 [implementation-progress.md](implementation-progress.md)。

## 存储边界

当前 JDBC 模块已经承担任务定义、`nextFireTime` CAS 和 HA 协调存储。调度节点仍应避免把全量数据库扫描放进每一次 tick 的热路径里。

```text
持久化配置/运行态       存储是事实来源，nextFireTime 用 CAS 推进
本地 TimingIndex        scheduler owner 的快速到期索引
shard lease             决定哪个节点可以扫描哪一片任务
fencing token           防止旧 owner 在恢复后继续写入
```

`JdbcJobRepository` 使用 `jobId + expected nextFireTime + ownerNodeId + fencingToken + leaseUntil` 推进调度游标并递增 `version`。execution/target 终态采用单向状态机，ACK、结果聚合和 timeout 通过父行锁串行化，避免故障切换中的状态回退。
