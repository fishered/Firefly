# HA Cluster Design

Firefly 的 HA 不是简单启动多个节点。调度系统必须解决两个问题：

- 多个 scheduler 节点不能重复触发同一个任务。
- 多个业务 executor 实例要共享同一个逻辑执行器能力。

## 节点角色

Firefly 节点可以拥有多个角色：

```text
SCHEDULER   负责持有 shard lease、扫描本地 TimingIndex、生成 ExecutionCommand
STANDBY     可以接管 scheduler 职责，但没拿到 lease 时不主动调度
GATEWAY     维护 executor Netty 长连接
API         暴露管理 API 和配置写入入口
EXECUTOR    业务执行节点
```

单机模式：

```text
node-1: SCHEDULER + GATEWAY + API
```

集群模式：

```text
node-1: SCHEDULER + GATEWAY + API
node-2: STANDBY + GATEWAY
node-3: STANDBY + GATEWAY
```

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

后续持久化实现必须在更新 `job_runtime_state` 时校验 fencing token。

## 避免重复触发

Firefly 使用多层保护：

```text
shard owner            正常情况下只有 owner 扫描任务
CAS nextFireTime       推进 runtime state 时校验 expected nextFireTime/version/token
executionId            jobId + scheduledFireTime 幂等
execution log unique   防止重复记录
executor idempotency   业务侧最终兜底
```

不要承诺绝对 exactly-once。更现实的目标是 effectively-once：

```text
尽量只触发一次 + 存储防重复 + 执行侧幂等
```

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

如果所有实例都不可用，任务进入 dispatch failure / misfire 策略，而不是假装执行成功。

## 当前落地代码

已落地：

- `NodeRole`
- `NodeStatus`
- `FireflyNode`
- `NodeRegistry`
- `InMemoryNodeRegistry`
- `ShardLease`
- `ShardManager`
- `InMemoryShardManager`
- `ShardHasher`
- `ExecutionCommand.ownerNodeId`
- `ExecutionCommand.fencingToken`
- `stores/jdbc`
- `JdbcSchema`
- `JdbcJobRepository`
- `JdbcNodeRegistry`
- `JdbcShardManager`

下一步：

- token-aware runtime state 和 execution log。
- scheduler 按 shard lease 加载本地 TimingIndex。
- Netty executor client 支持多个 gateway seed address 重连。
- gateway 根据 fencing token 拒绝过期 command。

## 存储边界

当前 JDBC 模块已经承担任务定义、`nextFireTime` CAS 和 HA 协调存储。调度节点仍应避免把全量数据库扫描放进每一次 tick 的热路径里。

```text
持久化配置/运行态       存储是事实来源，nextFireTime 用 CAS 推进
本地 TimingIndex        scheduler owner 的快速到期索引
shard lease             决定哪个节点可以扫描哪一片任务
fencing token           防止旧 owner 在恢复后继续写入
```

`JdbcJobRepository` 目前用 `jobId + expected nextFireTime` 推进调度游标，并递增 `version`。后续实现 token-aware runtime state 时，需要把 `ownerNodeId`、`fencingToken` 也放进同一个 CAS 更新边界。这样即使旧节点恢复，它携带的旧 token 也无法推进任务游标。
