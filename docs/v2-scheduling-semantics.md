# Scheduling Semantics Feature

## 当前实现状态（1.1.x）

已落地并可运行：`CalendarDefinition`/日历评估、黑名单 `SKIP`/`DELAY_TO_END` 决策、任务定义中的日历与黑名单配置、依赖环检测、事件 inbox 幂等模型、有限补数展开，以及 Admin HTTP 的事件和补数入口。事件和补数都复用普通 `ExecutionCommand` 与 Dispatch Outbox；JDBC inbox 实现已提供，HTTP 默认使用内存实现。

依赖状态已按业务时间窗口从 execution/outbox 状态查询，保存任务时执行环检测；事件入口提供 HMAC-SHA256 签名校验器（时间窗默认由调用方配置），JDBC inbox 使用唯一幂等键。生产环境仍需在网关层强制启用签名校验，并补充日历/依赖管理 UI 与运维查询页面。

本 Feature 负责在现有 Cron、fixed-rate、misfire、时区和并发策略之上增加业务日历、黑名单窗口、轻量依赖、事件触发和补数，不引入任意 DAG 图引擎。

## 实施拆分

1. `CalendarDefinition` 和版本化日历存储：输入使用任务时区，计算输出转换为 UTC；日历更新只影响尚未展开的触发时间。
2. `BlackoutWindow`：在 `SchedulerEngine` 生成 execution 前计算 `SKIP` 或 `DELAY_TO_END`，并写入可审计的调度决策。
3. `JobDependency`：只允许同一调度域内的前置 job，按相同业务时间窗口判断成功；依赖未满足时保留当前游标并进入有限重试。
4. `EventTrigger`：事件 key 在 `firefly_trigger_inbox` 做幂等，确认后通过普通 dispatch Outbox 创建 execution。
5. `BackfillRequest`：把闭区间时间窗口展开为有界请求，复用任务并发策略和 execution root id。

## 代码边界

- `libs/scheduler-core`: `schedule/CalendarDefinition`、`schedule/BlackoutWindow`、`schedule/JobDependency`、`trigger/EventTrigger`、纯计算服务和决策结果。
- `stores/jdbc`: 日历、依赖和 trigger inbox 的 JDBC 实现；所有触发确认必须使用 CAS/唯一键。
- `apis/admin-http`: 预览日历、提交补数、发布事件和查询调度决策。
- `ui/admin`: 在任务编辑页增加日历/黑名单/依赖/补数配置，在执行页显示被跳过或延迟原因。

## 验收重点

- DST 重复小时不会丢失合法触发，春季不存在时间不会产生伪造 UTC 时间。
- 重复事件只产生一个 root execution；并发补数不会越过 `FORBID` 或执行器容量。
- 依赖等待、黑名单跳过和 misfire 结果可解释、可审计且不推进错误游标。
- 事件入口的 payload、key 和签名都有大小限制，失败事件可查询和重新确认。
