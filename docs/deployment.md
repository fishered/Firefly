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

高可用远程执行器场景下，Scheduler 在共享数据库中原子写入 execution 和 outbox。Outbox 会标记为 `LOCAL` 或 `REMOTE`：Scheduler 节点领取本地任务，Gateway 节点领取远程任务。Executor 可同时连接所有 Gateway；也可启用共享实例位置目录和 Gateway 内部转发，使领取 Outbox 的 Gateway 将目标帧转发到实际持有连接的 Gateway。

Outbox 轮询、批量、ACK 超时、重试上限、维护周期和数据库时钟校准均可通过 `firefly.dispatch.outbox.*`、`firefly.execution.maintenance.*` 和 `firefly.jdbc.clock.*` 配置。Gateway 的结果持久化队列和最大消息帧大小可通过 `firefly.executor.gateway.netty.result-queue-capacity`、`firefly.executor.gateway.netty.max-frame-length` 配置。

Outbox worker 的状态回写由 `claim_owner + attempt` fencing 保护。认领租约过期并被其他节点接管后，旧 worker 的迟到 SENT/RETRY 更新会失败；`DONE` 和 `DEAD` 也不会被普通重试路径重新激活。`max-attempts` 是包含 ACK 超时在内的真实发送次数上限。监控应至少对 `firefly_jobs_overdue_max_seconds`、`firefly_dispatch_outbox_oldest_age_seconds`、`firefly_dispatch_outbox_dead`、`firefly_dispatch_outbox_delivery_exhausted_total` 和 `firefly_shard_lease_renewal_failures_total` 建立告警。

Gateway 支持两种拓扑：3 个全角色节点可让 Executor 同时连接全部 Gateway；节点更多时启用内部转发。每个 Gateway 配置唯一的 `firefly.executor.gateway.internal.advertised-address` 和监听端口，所有节点使用相同的 `firefly.executor.gateway.internal.auth-token`。共享目录使用 `instanceId + sessionId + lease` 防止旧连接被路由；内部端口应只在集群可信网络开放。

内部转发使用 HMAC-SHA256 签名，不在请求中传输共享 Token，并校验 30 秒时间窗、nonce 防重放和请求体上限。仍应通过安全组或 NetworkPolicy 限制内部端口来源；HTTP 不提供载荷保密性，跨不可信网络应在服务网格或内部负载层终止 mTLS。

```properties
firefly.executor.gateway.internal.host=0.0.0.0
firefly.executor.gateway.internal.port=9801
firefly.executor.gateway.internal.advertised-address=http://gateway-1:9801
firefly.executor.gateway.internal.auth-token=${FIREFLY_EXECUTOR_GATEWAY_INTERNAL_AUTH_TOKEN}
firefly.executor.gateway.instance-location-refresh-interval=PT30S
firefly.executor.gateway.instance-location-lease=PT90S
```

节点排空使用 `POST /api/nodes/{nodeId}/drain`。节点进入 `DRAINING` 后会停止领取新 Outbox、释放 Scheduler 分片、拒绝新 Executor 注册，但保留已有连接完成在途任务。可通过 `GET /api/nodes/{nodeId}/drain-status` 查看剩余分片、投递、target 和连接；持久化在途工作归零后节点自动断开空闲 Executor 并转为 `OFFLINE`。

Scheduler 容量参数为 `firefly.scheduler.max-due-records-per-tick` 和 `firefly.scheduler.max-idle-wakeup`。生产环境应加载 `config/prometheus/firefly-alerts.yml`，再根据压测结果调整 p99 阈值和 per-shard backlog 阈值。

集群生产环境建议关闭 `FIREFLY_EXECUTOR_REGISTRATION_AUTO_CREATE_DEFINITION`。先通过 `POST /api/executor-definitions` 创建逻辑执行器，再由业务服务使用相同的 `executorName` 注册一个或多个运行实例；服务断线只会使实例离线，不会删除执行器定义。

数据库全量初始化和旧库升级说明见 [database-schema.md](database-schema.md)。当前 schema v9 还包含共享 Executor 位置目录、持久化审计和任务变更历史。

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

所有 Admin 变更请求会输出到 `com.firefly.audit.admin` logger。生产环境应将该 logger 单独送入不可变日志存储；记录不会包含 Token 和请求体。

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

业务 Executor 容器需要跨重启复用已完成结果时，应为 `firefly.executor.idempotency-directory` 挂载持久卷，例如 `/data/firefly-executor-results`，并配置足以覆盖调度重投窗口的 `idempotency-retention`。该存储能重放已完成结果，但进程在业务副作用完成、结果落盘之前崩溃时仍可能再次执行，Handler 仍应按 `rootExecutionId` 或业务键保证幂等。

Gateway TLS 证书、私钥和信任链会按 `firefly.executor.gateway.netty.tls.reload-interval` 检查变化。刷新只作用于新连接，已有 TLS 会话保留到自然重连。
