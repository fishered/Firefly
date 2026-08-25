# Firefly v2 平台化大版本设计

本文定义 Firefly v2 的产品边界、模块拆分、数据契约和分支交付策略。v2 的目标不是把 Firefly 变成完整 DAG 平台，而是在现有调度核心之上形成可观测、可恢复、可扩展的生产任务平台。

## 1. 版本目标

v2 以“任务运维闭环”为主线，逐步补齐四类能力：

1. `operations`：执行详情、失败恢复、超时和连续失败告警、日志查询与归档。
2. `scheduling-semantics`：工作日/节假日历、黑名单时间段、轻量前置依赖、事件触发和补数。
3. `batch-processing`：分片进度、分片级结果、失败分片重试、SLA 统计和批处理任务上下文。
4. `cloud-native`：Kubernetes 部署、角色独立扩缩容、健康检查、OTLP/Grafana/告警集成。

四个 Feature 共享现有的 execution、Outbox、lease/fencing、Prometheus 和 OpenTelemetry 边界，不把 HTTP、JDBC 或 UI 代码移动到 `libs/scheduler-core`。

## 2. 当前架构到 v2 的映射

| v1 基础 | v2 复用方式 |
| --- | --- |
| `SchedulerCatalog` | 保存任务、执行器、任务组以及调度增强配置 |
| `JobRepository` | 保存运行游标、分片归属和 CAS 推进 |
| `ExecutionRepository` | 承载父执行、target、状态机和 attempt 链路 |
| `JobHistoryRepository` | 承载可查询历史和归档边界 |
| `DispatchOutboxStore` | 保证恢复、重放和重新派发的可靠意图 |
| `NodeRegistry` / `ShardManager` | 限制告警扫描、维护扫描和聚合任务的单节点执行 |
| `SchedulerMetrics` / tracing | 提供时延、失败、积压和链路观测 |
| `apis/admin-http` | 对外提供稳定 JSON API 和 RBAC 路由策略 |
| `ui/admin` | 提供执行运维和告警处理工作台 |

## 3. Feature 分支和 PR 依赖

```text
feature/v2-platform-design
        |
        +--> feature/v2-operations-mvp
        |
        +--> feature/v2-scheduling-semantics
        |
        +--> feature/v2-batch-processing
        |
        `--> feature/v2-cloud-native
```

设计分支只包含设计和契约；每个实现分支从设计分支切出，完成代码、测试和迁移脚本后提交独立 PR。`operations-mvp` 优先合并，`batch-processing` 可复用其中的日志、告警和执行详情模型；调度语义和云原生不依赖 UI 内部实现。

## 4. Operations MVP

### 4.1 目标闭环

```text
执行产生状态/错误
    -> execution history 和日志事件落库
    -> rule evaluator 生成告警事件
    -> Admin 查询、确认、静默或手工恢复
    -> retry/replay/cancel 复用现有 Outbox 和 execution 状态机
```

MVP 包含：

- 执行详情：父执行、target、attempt、owner、gateway、ACK、完成时间、timeout 和失败原因。
- 失败恢复：失败 target 重试、整次 execution 重放、Dead Outbox 重新入队，所有写操作带幂等键和审计日志。
- 告警：单次超时、连续失败、执行延迟超过阈值、Outbox Dead 数量超过阈值。
- 日志：按 execution、root execution、job 和时间范围查询，支持分页、级别过滤和 traceId 关联。

### 4.2 核心模块

```text
libs/scheduler-core
  execution/ExecutionEvent.java
  execution/ExecutionEventStore.java
  alert/AlertRule.java
  alert/AlertEvent.java
  alert/AlertEvaluator.java
  log/ExecutionLogRecord.java
  log/ExecutionLogStore.java

stores/jdbc
  JdbcExecutionEventStore.java
  JdbcExecutionLogStore.java
  JdbcAlertStore.java
  schema/v12__operations.sql

apis/admin-http
  AdminOperationsController.java
  AdminAlertController.java
  AdminLogController.java

ui/admin
  执行详情、告警中心、日志查询页面
```

核心接口保持同步、无网络依赖；日志采集和告警投递在 runtime worker 中异步执行。数据库实现负责分页、索引和租约，内存实现用于单元测试。

### 4.3 建议数据模型

`firefly_execution_event`：`event_id`、`execution_id`、`root_execution_id`、`job_id`、`run_attempt`、`event_type`、`status`、`occurred_at`、`trace_id`、`payload`。事件按 execution 顺序追加，不更新历史事件。

`firefly_execution_log`：`log_id`、`execution_id`、`root_execution_id`、`job_id`、`run_attempt`、`target_id`、`level`、`message`、`logger`、`trace_id`、`created_at`、`retention_until`。消息大小和每次批量写入数量必须受配置限制。

`firefly_alert_rule`：`rule_id`、`job_id`、`type`、`threshold`、`window`、`cooldown`、`enabled`、`version`。

`firefly_alert_event`：`alert_id`、`rule_id`、`job_id`、`execution_id`、`fingerprint`、`severity`、`status`、`first_seen_at`、`last_seen_at`、`acknowledged_by`、`acknowledged_at`、`payload`。`fingerprint + active status` 唯一，避免扫描周期重复生成告警。

### 4.4 Admin API

```text
GET  /api/executions/{executionId}/timeline
GET  /api/executions/{executionId}/logs?cursor=&limit=&level=
GET  /api/logs?jobId=&rootExecutionId=&from=&to=&level=&cursor=&limit=
GET  /api/alerts?status=&severity=&jobId=&cursor=&limit=
POST /api/alerts/{alertId}/acknowledge
POST /api/alerts/{alertId}/silence
POST /api/executions/{executionId}/retry
POST /api/executions/{executionId}/replay
```

读接口需要 `READER`；重试、重放、确认和静默需要 `OPERATOR`；规则配置和删除需要 `ADMIN`。重试和重放通过 `Idempotency-Key` 防止双击造成多个 attempt，实际状态变化仍由 execution CAS 和 Outbox fencing 保证。

### 4.5 告警语义

- `TIMEOUT`：execution 进入 `TIMEOUT` 后产生单次告警，恢复条件是下一 attempt 成功或人工关闭。
- `CONSECUTIVE_FAILURE`：按 `rootExecutionId` 统计连续失败，达到阈值后生成/更新同一 fingerprint 告警。
- `LATENCY`：用数据库校准时间计算从计划触发到开始执行的延迟，避免使用节点本地时钟。
- `OUTBOX_DEAD`：按 dispatch type 和 job 聚合 Dead 数量，超过阈值生成告警。

告警 evaluator 只由 shard 0 owner 执行，使用数据库时间和短事务；通知器通过 `WebhookNotifier` SPI 扩展，Slack/钉钉适配器放在 integrations，不进入核心。

## 5. Scheduling Semantics Feature

先实现不改变 DAG 拓扑的语义：

- `CalendarDefinition`：工作日、法定节假日和交易日历，版本化并绑定任务时区。
- `BlackoutWindow`：禁止触发窗口，明确 `SKIP`、`DELAY_TO_END` 两种策略。
- `JobDependency`：只支持同一调度域内的轻量前置依赖，默认要求前置任务对应时间窗口成功；不支持任意图遍历。
- `EventTrigger`：接收带幂等 key 的外部事件，写入 trigger outbox 后生成普通 execution。
- `BackfillRequest`：把历史时间窗口展开成有界 execution 请求，复用并发、幂等和 Outbox。

所有调度计算使用任务时区输入、UTC runtime cursor 输出。DST、misfire、依赖未完成和重复事件必须有明确的拒绝或延迟结果，不能静默跳过。

## 6. Batch Processing Feature

Batch 任务是现有 broadcast/sharding 的业务化封装，不重新实现派发协议：

- `BatchExecution` 聚合 root execution、总分片数、已完成/失败/重试/跳过计数和进度更新时间。
- `BatchTargetResult` 记录每个 shard 的业务结果摘要、输入窗口、输出计数和 checkpoint。
- 失败分片重试只创建新的 attempt，沿用 root execution 幂等键；成功分片不重复派发。
- 提供 SLA、吞吐、分片失败率和处理进度指标。

分片进度上报必须限频、限大小并允许丢弃非关键中间进度；最终结果和 checkpoint 不能丢失。大 payload 放对象存储，execution 表只保存摘要和引用。

## 7. Cloud Native Feature

云原生只包装已有角色边界：

- `firefly-scheduler`：Scheduler + maintenance，按 shard backlog 扩缩容。
- `firefly-gateway`：Gateway + Outbox remote worker，按连接数和 dispatch backlog 扩缩容。
- `firefly-api`：Admin API，按 HTTP 延迟和请求量扩缩容。
- `firefly-admin-ui`：独立 Node UI。

交付内容包括 Helm chart、PDB、NetworkPolicy、ServiceMonitor、HPA、滚动升级检查、OTLP Collector 示例和 Grafana dashboard。Scheduler 仍依赖 lease/fencing，Gateway 仍依赖 Outbox claim；Kubernetes 不替代数据库协调。

## 8. 版本兼容和迁移

- 新表采用 additive migration，旧节点可读旧表，新字段提供默认值。
- v2 写入的新事件、日志和告警表不改变 v1 execution 状态机字段含义。
- Admin API 新接口独立于现有路径；请求字段增加只允许向后兼容，破坏性变化进入 `/api/v2`。
- 保留期、日志采样、payload 上限和通知重试均配置化，默认关闭外部通知。
- 发布顺序：先迁移表和只读 API，再启用异步写入，最后打开告警和 UI 操作入口。

## 9. 验收和发布门槛

每个 Feature PR 必须包含：

- 核心接口和内存实现测试；
- JDBC/H2 schema 初始化、迁移和分页测试；
- Admin 路由、RBAC、幂等和审计测试；
- 至少一个跨节点 fencing 或故障恢复测试；
- 指标、日志和配置说明；
- PR 描述中的回滚方式和数据兼容说明。

Operations MVP 的发布门槛：告警重复率为零、重试/重放重复提交不会产生额外 attempt、日志分页在保留期内稳定、执行状态与时间线最终一致、Dead Outbox 恢复后可在现有 worker 中完成派发。

