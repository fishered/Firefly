# 多租户资源治理

Firefly 1.2.0 在共享集群中把租户作为一等资源治理边界。所有 Gateway 与 Scheduler 通过同一 JDBC 配额账本执行原子准入；内存模式保留相同语义，但只用于单节点开发。

## 任务归属与资源声明

任务仍使用兼容的 `parameters` 扩展，不改变 Executor v1/v2 wire protocol。旧任务和空租户自动归入 `default`：

```properties
firefly.resource.tenantId=payments
firefly.resource.priority=6
firefly.resource.cpuMillis=500
firefly.resource.memoryBytes=268435456
```

优先级范围为 `0..9`，默认 `0`。优先级只影响尚未派发的任务；1.2.0 不终止或回滚正在运行的任务。

## 配额

每个租户可动态配置：

- 最大并发执行数；
- 预留 CPU milli；
- 预留内存 bytes；
- 最大 outstanding backlog；
- 每秒外部触发数；
- 加权公平调度权重和启停状态。

配额值 `0` 表示不限制。backlog 从 execution 被可靠接收入 Outbox 起计数，到成功、失败终态、取消或死信结束时释放，因此同时覆盖等待和在途工作，避免大量运行中任务绕过 outstanding 上限。

Admin API 使用乐观锁更新，调整后无需重启：

```http
GET /api/tenants
GET /api/tenants/payments
PUT /api/tenants/payments
Content-Type: application/json

{
  "enabled": true,
  "weight": 3,
  "maxConcurrent": 40,
  "maxCpuMillis": 20000,
  "maxMemoryBytes": 34359738368,
  "maxBacklog": 100000,
  "maxTriggerRatePerSecond": 200,
  "expectedVersion": 4
}
```

并发更新只有一个版本可以成功；过期版本返回 `409 tenant_version_conflict`。手动触发和 replay 的积压准入失败返回 HTTP 429，事件与 Backfill 保存稳定原因码。派发等待原因写入 Outbox `lastError`。

稳定原因码包括 `TENANT_NOT_FOUND`、`TENANT_DISABLED`、`CONCURRENCY_QUOTA_EXCEEDED`、`CPU_QUOTA_EXCEEDED`、`MEMORY_QUOTA_EXCEEDED`、`BACKLOG_QUOTA_EXCEEDED` 和 `TRIGGER_RATE_QUOTA_EXCEEDED`。

## 公平调度

同一有效优先级内使用持久化的加权公平队列。每个租户的虚拟完成时间保存在 `firefly_dispatch_fairness_state`，多个 Gateway/Scheduler 和连续的小批次 claim 共享同一进度；因此即使每批只领取一条，也不会因批次重置而长期偏向高权重租户。数据库在一次公平选择中锁定全局公平状态，候选 Outbox 的 claim 与虚拟时间推进在同一事务提交，避免并发节点重复派发或破坏权重比例。Worker 每批读取最新租户权重，配置变更无需重启。

每经过一个 `firefly.tenant.fairness.aging-interval`，等待项提升一级，最高为 9；因此最低优先级任务在九个 aging interval 后进入最高优先级公平集合，不会被持续高优流量永久饿死。优先级只决定当前参与选择的最高有效等级，同等级内仍按租户权重公平分配。

## HA 与计数恢复

JDBC 通过租户 policy/usage 行锁和 reservation ledger 串行化多节点准入。reservation ID 幂等，重复投递不会重复占用；运行租约过期由 shard-0 maintenance owner 回收。积压 reservation 使用 execution/outbox ID，对账任务会在同一事务中重新锁定并确认 Outbox 状态；即使远程派发已 ACK、Outbox 已为 `DONE`，只要 execution 尚未终态仍保留 backlog。对账只回收“准入已提交但生产者在 Outbox 提交前崩溃”或终态清理中断留下的孤儿记录。

派发状态迁移使用 claimant 与 attempt fencing。节点失联并由其他节点接管后，旧节点的迟到完成回调不能完成新 attempt，也不能释放接管节点持有的运行资源；超时重试会先释放旧 attempt 的运行与 backlog reservation，再对新 Outbox 执行准入，避免低 backlog 配额下自锁。

## 指标与告警

Prometheus 暴露：

- `firefly_tenant_usage{tenant,resource}`；
- `firefly_tenant_quota_limit{tenant,resource}`；
- `firefly_tenant_weight{tenant}` 与 `firefly_tenant_enabled{tenant}`；
- `firefly_tenant_admission_rejections_total{tenant,reason}`。

`config/prometheus/firefly-alerts.yml` 包含拒绝、90% 水位和 backlog 耗尽告警。Admin 的“租户治理”页显示动态配额与权威用量，所有 PUT 变更进入审计日志。

## 生产门禁

自动化测试覆盖多 Gateway 原子准入、重复 reservation、动态 CAS 更新、noisy-neighbor 权重、跨节点单条 claim 的持久公平性、有界饥饿、十万级积压、并发 claim 不重复、HA 接管后的迟到回调 fencing、超时/终态释放、孤儿对账、1.1.7 滚动写入以及旧任务 `default` 兼容。PostgreSQL/MySQL Testcontainers 门禁重复验证跨连接无超卖与跨 Gateway 公平性。

