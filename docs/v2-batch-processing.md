# Batch Processing Feature

## 当前实现状态（1.1.x）

已落地并可运行：`BatchExecution`、`BatchProgress`、`BatchShardResult`、`BatchCheckpoint` 聚合模型，内存与 JDBC 批处理仓储（带 fencing 条件更新），分片结果/进度聚合器，Netty 结果帧的受限计数与 checkpoint 摘要字段，以及 `GET /api/batches/{rootExecutionId}` 查询。

Netty gateway 已在持久化执行结果后异步写入批处理仓储；进度按 1 秒窗口限频且终态强制落库，Prometheus 已暴露批处理更新/丢弃/失败计数。大结果通过 BatchObjectStore SPI 保存，数据库只记录 location/checksum/count。生产环境仍需注入 S3/OSS 实现并配置告警阈值。

本 Feature 把已有 broadcast/sharding 派发能力包装成数据同步和批处理任务模型，保持现有 execution、target、retry 和 Outbox 协议不变。

## 实施拆分

1. 增加 `BatchExecution` 只读聚合：root execution、总分片、完成/失败/重试计数、最后进度时间和 SLA 状态。
2. 增加分片结果摘要和 checkpoint 引用；大结果放对象存储，数据库只保存摘要、计数和校验值。
3. 分片进度使用限频批量写入，高水位丢弃中间进度但不丢最终结果；最终结果沿用 target 状态 CAS。
4. 失败分片只创建新 attempt，成功分片生成 carry target，复用现有 retry scope 和 root 幂等键。
5. 增加吞吐、处理进度、分片失败率、SLA 和 checkpoint 恢复指标。

## 代码边界

- `libs/scheduler-core`: `batch/BatchExecution`、`batch/BatchProgress`、分片摘要校验和进度策略。
- `stores/jdbc`: batch 聚合、checkpoint 和最终结果存储，增加按 root/shard 的索引。
- `transports/netty`: 仅扩展协议消息模型，不在 EventLoop 执行 JDBC 或对象存储操作。
- `apis/admin-http` / `ui/admin`: 批处理详情、进度、失败分片筛选和单分片重试。

## 验收重点

- 成功分片不会因父 execution 重投而再次执行业务 Handler。
- gateway 切换、连接重建和迟到结果不会覆盖新的 fencing token。
- 进度写入有界，批量任务大 payload 不会撑爆 execution 表或 Netty 帧。
- checkpoint 恢复可重试指定分片，并能在 execution timeline 中定位。
