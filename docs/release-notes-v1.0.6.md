# Firefly v1.0.6 Release Notes

Firefly v1.0.6 聚焦 Scheduler 恢复能力、单节点并发边界和集群运行成本。本版本不修改数据库
Schema、Executor 协议或任务定义格式，可在现有 v1.x 数据库上直接升级。

## 调度正确性

- Scheduler 从本地 TimingIndex 取出到期任务后，如果游标推进、Outbox 写入或本地派发抛出异常，
  会标记索引强制重载。下一次 tick 重新读取仓储状态，不再等待配置变更、分片变化或节点重启。
- 配置 revision 检查使用独立刷新周期，不再跟随每次空闲唤醒查询数据库。
- 强制重载拥有独立 `reloadRequired` 状态，不受 revision 刷新周期限制。

## 单节点并发与关闭

- 本地 Handler 保留 Java 21 虚拟线程模型，同时增加非阻塞 admission，默认最多接受 256 个在途任务。
- admission 饱和时立即拒绝提交，由现有 Dispatch Outbox 重试链路承担回压，不在 Scheduler 线程上等待容量。
- `FORBID` 使用 CAS 原子抢占；执行记录首次写入失败、线程池拒绝和 Handler 异常都会释放运行计数。
- completion 在运行计数释放后完成，调用方观察到任务结束时可以立即重新提交。
- worker pool 默认优雅等待 30 秒，超时后中断剩余任务并再次执行有界等待。

## 集群与数据库

- `ShardManager` 增加兼容默认实现的 `renewAll` 批量续租接口。
- JDBC 实现每轮续租只获取一个连接、读取一次数据库时间，并通过 `executeBatch` 更新本节点持有的 lease。
- 每个 lease 仍校验 `shard_id`、`owner_node_id`、`fencing_token` 和有效期，fencing 语义不变。
- 非 `DRAINING` 节点的周期 Drain 检查直接返回，不再执行 shard、Outbox 和 execution target 聚合。

## HTTP 与可观测性

- Admin HTTP：32 个工作线程、256 个等待槽位和明确拒绝策略。
- Prometheus HTTP：8 个工作线程、64 个等待槽位和明确拒绝策略。
- Gateway 内部转发：32 个工作线程、256 个等待槽位；关闭时显式终止所属 executor。
- 单次 Prometheus 抓取只读取一次 Outbox 状态统计和一次 Execution 状态统计。
- 新增指标：
  - `firefly_local_worker_active`
  - `firefly_local_worker_max_concurrency`
  - `firefly_local_worker_rejections_total`

## 新增配置

| 配置 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `firefly.worker.max-concurrency` | `FIREFLY_WORKER_MAX_CONCURRENCY` | `256` | 本地 Handler 最大在途数 |
| `firefly.worker.shutdown-timeout` | `FIREFLY_WORKER_SHUTDOWN_TIMEOUT` | `PT30S` | worker 关闭等待期限 |
| `firefly.scheduler.configuration-refresh-interval` | `FIREFLY_SCHEDULER_CONFIGURATION_REFRESH_INTERVAL` | `PT1S` | revision 查询周期 |

所有数值必须为正数。配置优先级仍为 CLI、环境变量、profile、主配置、代码默认值。

## 兼容性与升级

- 无数据库 Schema 迁移。
- 无 Netty/Executor 协议变更。
- 无 Admin API 请求或响应格式变更。
- worker 饱和时，本地任务可能更早进入 Outbox 重试，而不是无限制创建虚拟线程。
- 配置修改最多在 `configuration-refresh-interval` 后进入 Scheduler 本地索引；异常恢复不受该延迟影响。

## 验证

本版本覆盖以下新增回归场景：

- 仓储首次推进失败后，下一 tick 重新加载并正常执行。
- 高频 500ms tick 下，配置 revision 在独立 1 秒周期到达后刷新。
- 16 个并发调用竞争同一个 `FORBID` 任务时仅接收一个执行。
- 首次 execution 持久化失败后可再次提交同一个 `FORBID` 任务。
- worker admission 饱和时立即拒绝并更新指标。
- 多个 shard lease 使用 JDBC batch 续租。
- Admin、Prometheus、Gateway 转发和 Drain 既有集成测试保持通过。

发布前质量门禁：

```powershell
E:\gradle-9.6.1\bin\gradle.bat test --no-daemon
```

当前文档对应 `future-runtime-optimizations-v1.0.6` 集成分支。创建 `v1.0.6` tag 和 GitHub Release 前，
仍需在最终目标分支执行完整回归并确认发布构件版本。
