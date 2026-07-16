# Firefly 镜像部署

Firefly 镜像是通用 server 镜像，不在构建时固化节点身份。节点身份和职责在运行容器时通过环境变量指定。

## 构建镜像

```powershell
docker build -t firefly:local .
```

## 单节点运行

```powershell
docker run --rm --name firefly `
  -p 9700:9700 `
  -p 9710:9710 `
  -p 9711:9711 `
  -e FIREFLY_NODE_MODE=standalone `
  -e FIREFLY_NODE_NAME=firefly-standalone `
  -e FIREFLY_NODE_ROLES=api,gateway,scheduler `
  -e FIREFLY_SCHEDULER_SHARD_COUNT=32 `
  -e FIREFLY_EXECUTOR_REGISTRATION_AUTO_CREATE_DEFINITION=true `
  -e FIREFLY_ADMIN_HTTP_HOST=0.0.0.0 `
  -e FIREFLY_ADMIN_HTTP_API_TOKEN=change-me `
  -e FIREFLY_METRICS_PROMETHEUS_HOST=0.0.0.0 `
  -e FIREFLY_CONFIG_PROFILE=memory `
  firefly:local
```

## 集群运行

集群模式必须使用共享存储。当前推荐先用 JDBC，例如 PostgreSQL。

```powershell
docker run --rm --name firefly-node-1 `
  -p 9700:9700 `
  -p 9710:9710 `
  -p 9711:9711 `
  -e FIREFLY_NODE_MODE=cluster `
  -e FIREFLY_NODE_NAME=firefly-node-1 `
  -e FIREFLY_NODE_ROLES=api,gateway,scheduler `
  -e FIREFLY_SCHEDULER_SHARD_COUNT=32 `
  -e FIREFLY_SCHEDULER_COORDINATION_RECONCILE_INTERVAL=PT1S `
  -e FIREFLY_SCHEDULER_COORDINATION_NODE_TIMEOUT=PT5S `
  -e FIREFLY_SCHEDULER_COORDINATION_LEASE_DURATION=PT10S `
  -e FIREFLY_EXECUTOR_REGISTRATION_AUTO_CREATE_DEFINITION=false `
  -e FIREFLY_EXECUTOR_AUTH_TOKEN=change-executor-token `
  -e FIREFLY_ADMIN_HTTP_HOST=0.0.0.0 `
  -e FIREFLY_METRICS_PROMETHEUS_HOST=0.0.0.0 `
  -e FIREFLY_STORE_TYPE=jdbc `
  -e FIREFLY_JDBC_URL=jdbc:postgresql://postgres:5432/firefly `
  -e FIREFLY_JDBC_USERNAME=postgres `
  -e FIREFLY_JDBC_PASSWORD=123456 `
  -e FIREFLY_JDBC_DIALECT=postgresql `
  firefly:local
```

第二、第三个节点使用同一个镜像，只需要换掉 `FIREFLY_NODE_NAME`。第一阶段推荐三个节点都运行：

```text
FIREFLY_NODE_ROLES=api,gateway,scheduler
```

`FIREFLY_SCHEDULER_SHARD_COUNT` 是集群首次初始化后不可变的契约。所有节点必须配置相同值；已有集群不能通过滚动修改该环境变量完成重分片。

需要拆专用节点时，可以改成：

```text
FIREFLY_NODE_ROLES=api
FIREFLY_NODE_ROLES=gateway
FIREFLY_NODE_ROLES=scheduler
```

高可用远程执行器场景下，Scheduler 在共享数据库中原子写入 execution 和 outbox。Outbox 会标记为 `LOCAL` 或 `REMOTE`：Scheduler 节点领取本地任务，Gateway 节点领取远程任务并通过本地 Netty 连接下发。因此可以部署纯 `scheduler` 和纯 `gateway` 节点，不需要 Gateway 内部 RPC。业务侧 Executor 客户端应同时连接所有 Gateway 地址。

Outbox 轮询、批量、ACK 超时、重试上限、维护周期和数据库时钟校准均可通过 `firefly.dispatch.outbox.*`、`firefly.execution.maintenance.*` 和 `firefly.jdbc.clock.*` 配置。Gateway 的结果持久化队列和最大消息帧大小可通过 `firefly.executor.gateway.netty.result-queue-capacity`、`firefly.executor.gateway.netty.max-frame-length` 配置。

集群生产环境建议关闭 `FIREFLY_EXECUTOR_REGISTRATION_AUTO_CREATE_DEFINITION`。先通过 `POST /api/executor-definitions` 创建逻辑执行器，再由业务服务使用相同的 `executorName` 注册一个或多个运行实例；服务断线只会使实例离线，不会删除执行器定义。

数据库全量初始化和旧库升级说明见 [database-schema.md](database-schema.md)。当前 schema v8 包含节点、集群元数据、分片租约、执行器、任务组、任务、带不可变快照和 attempt 链的事务 Outbox，以及带不可变超时截止时间的执行父记录和目标子记录。

需要修改 scheduler shard count 时必须停机执行显式维护命令；工具会拒绝在线节点、活跃 execution 和未完成 Outbox，并重算 `firefly_job.shard_id`、清理旧 shard lease、更新集群元数据：

```powershell
.\gradlew.bat :server:launcher:migrateSchema --args="--firefly.schema.action=reshard --firefly.schema.reshard.confirm=true --firefly.scheduler.shard-count=64"
```

Admin HTTP 支持分级 Token。旧 `FIREFLY_ADMIN_HTTP_API_TOKEN` 仍按 `ADMIN` 处理；生产环境建议分别配置只读、运维和管理 Token：

```text
FIREFLY_ADMIN_HTTP_READER_TOKEN=reader-token
FIREFLY_ADMIN_HTTP_OPERATOR_TOKEN=operator-token
FIREFLY_ADMIN_HTTP_ADMIN_TOKEN=admin-token
```

## Compose 示例

仓库根目录提供 `docker-compose.yml`，可启动 PostgreSQL 和三个 Firefly 全角色节点：

```powershell
docker compose up --build
```

默认暴露：

```text
Admin HTTP: http://127.0.0.1:9710/
Metrics:    http://127.0.0.1:9711/metrics
Gateway:    127.0.0.1:9700
```

业务 Executor 容器需要跨重启复用已完成结果时，应为 `firefly.executor.netty.idempotency-directory` 挂载持久卷，例如 `/data/firefly-executor-results`，并配置足以覆盖调度重投窗口的 `idempotency-retention`。该存储能重放已完成结果，但进程在业务副作用完成、结果落盘之前崩溃时仍可能再次执行，Handler 仍应按 `rootExecutionId` 或业务键保证幂等。
