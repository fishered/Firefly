# 从 Firefly 1.1.7 升级到 1.2.0

1.2.0 保持 Executor 协议 v1/v2、插件 API level 1、既有任务定义和 Admin API 默认用法兼容。数据库 schema 兼容版本仍为 `12`；v20 feature migration 新增租户 policy/usage/reservation 与派发公平状态表，并为 Outbox 增加带默认值的 `tenant_id`、`queue_priority` 列。

## 推荐滚动顺序

1. 备份数据库，先执行对应方言的 `v20.sql`，或由首个 1.2.0 节点以 `initialize-if-empty` 安装。
2. 升级期间保持 `default` 租户配额为 `0`（无限），不要提前启用业务租户硬配额。1.1.7 节点会忽略新增表和列，旧写入自动得到 `default`/`0`。
3. 依次排空并替换 Scheduler、Gateway/API 节点；Executor 无需升级。
4. 确认所有控制面节点均为 1.2.0，检查 `firefly_tenant_usage`、`firefly_dispatch_fairness_state`、租户指标和 Outbox 对账无漂移。
5. 创建业务租户，给任务写入 `firefly.resource.tenantId`，再逐步收紧并发、CPU、内存、backlog 与触发速率配额。

混合版本窗口只用于兼容滚动，不用于启用硬配额：1.1.7 节点不了解租户账本。启用非零配额后若必须回滚，应先停止新准入并排空旧版本将承担的 Scheduler/Gateway 角色，否则旧节点会绕过 1.2.0 配额。

## 验证

- `GET /api/tenants` 能看到 `default` 及权威用量；
- 旧任务返回 `tenantId=default`、`queuePriority=0`；
- 多 Gateway 并发触发不会超过租户上限；
- `firefly_tenant_admission_rejections_total` 可按租户和原因查询；
- 滚动过程中没有重复派发，升级完成并启用配额后没有超卖或长期饥饿。

