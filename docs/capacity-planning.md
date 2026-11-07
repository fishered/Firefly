# 配额预演与容量规划

Firefly 1.2.1 在 1.2.0 权威租户配额账本之上提供只读规划闭环。预演不会调用 `tryReserve`，不会修改 policy、usage、reservation、Outbox 或审计记录；真正应用仍使用 `PUT /api/tenants/{tenantId}` 和 `expectedVersion` CAS。

## 预演与推荐

```http
POST /api/tenants/{tenantId}/quota-preview
```

请求包含完整 proposed policy、`expectedVersion`、`window`（P1D..P30D）和 `headroomPercent`（5..100）。每个资源返回当前值、历史平均/峰值、触顶比例、拒绝数、proposed/recommended 值、质量和稳定原因码。

推荐算法固定为 `ceil(max(current, peak) * (1 + headroom))`。覆盖率低于 80%、样本被配额截断、窗口混合 policy version 或运行时版本时不生成伪精确下调建议。`0` 仍表示无限，不会因零负载自动建议改为零。

预览后若 policy version 已变化，应用返回 `409 tenant_version_conflict`。降低到当前用量以下只影响后续准入，不抢占运行中任务，并返回 `CRITICAL / CURRENT_USAGE_ABOVE_PROPOSED_LIMIT`。

## 容量报告与导出

```http
GET /api/tenant-capacity?window=P7D&risk=HIGH&cursor=&limit=100
GET /api/tenant-capacity/{tenantId}?window=P7D
GET /api/tenant-capacity/export?window=P7D&format=csv
```

列表/详情需要 `READER`，导出需要 `OPERATOR`。列表按 `risk desc, tenantId asc` 使用不透明 cursor，单页最大 500；导出最多 1,000 个租户和 2 MiB，超过上限快速失败。

## 采集、一致性与背压

- 默认桶宽 5 分钟、保留 7 天；桶宽允许 1 分钟至 1 小时，保留期允许 1 至 30 天。
- 准入线程只增加内存计数。缓冲满或存储故障时丢弃规划样本并记录低基数指标，不反压准入、Outbox 或执行终态事务。
- 每个进程使用唯一 source ID；`flushId = sourceId + tenantId + bucketStart`。flush dedupe 与样本合并在同一事务中，重试不会重复拒绝计数。
- shard 0 owner 读取 policy 与 usage 的一致快照；HA 接管和多节点采集对用量取 max，对首次 flush 的拒绝计数求和。
- v21 只新增 `firefly_tenant_capacity_sample` 和 `firefly_tenant_capacity_flush`，核心 schema 兼容版本仍为 12。

相关配置：

```properties
firefly.tenant.capacity.bucket-width=PT5M
firefly.tenant.capacity.sample-interval=PT1M
firefly.tenant.capacity.retention=P7D
firefly.tenant.capacity.max-pending-buckets=10000
firefly.tenant.capacity.cleanup-batch=500
```

## 指标

- `firefly_tenant_capacity_observations_dropped_total{reason}`
- `firefly_tenant_capacity_flush_failures_total{reason}`
- `firefly_tenant_capacity_flush_lag_seconds`
- `firefly_tenant_quota_preview_total{risk}`

这些健康指标不包含 tenant ID，避免租户数扩大 Prometheus 基数。
