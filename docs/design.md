# Design

## 模块边界

```text
server
 └─ Guice SchedulerModule
     └─ libs/scheduler-core
         ├─ SchedulerEngine
         ├─ JobDispatcher
         ├─ JobRepository
         └─ JobHandlerRegistry
```

`libs/scheduler-core` 是纯 Java 核心，不依赖 Spring、Guice、HTTP、数据库。

`server` 负责启动入口和 Guice 装配，后续 HTTP API、配置加载也放在这一层或单独拆到 `apis/http`。

调度中心的任务组、执行器、服务实例注册、心跳和持久化边界见 [scheduler-center.md](scheduler-center.md)。

## 调度循环

1. `SchedulerEngine` 定时扫描 `JobRepository.findDue(now)`。
2. 对每个到期任务，根据 `misfirePolicy` 算出本轮需要触发的 fire time。
3. 先 CAS 更新 `nextFireTime`，成功后再派发。
4. `JobDispatcher` 根据 `handlerName` 找到 `JobHandler`。
5. 根据 `concurrencyPolicy` 决定是否允许并发执行。

当前内存仓库使用 `nextFireTime` 有序索引查询 due job，避免每次 tick 全量扫描任务集合。

`SchedulerEngine` 单次 tick 会按批次 drain 多批 due job，默认每批 100 个、每次 tick 最多 10 批，以降低大量任务同时到期时的调度延迟。

## 时间模型

所有持久化时间建议使用 UTC `Instant`。

任务的业务时区放在 `JobDefinition.zoneId` 中，Cron 计算时使用该时区。这样可以表达：

- 中国每天 09:00：`zoneId=Asia/Shanghai`
- 纽约每天 09:00：`zoneId=America/New_York`
- 东京每周一 08:00：`zoneId=Asia/Tokyo`

## 后续扩展点

- `JobRepository`：替换为 JDBC、Redis、Raft store。
- `JobHandlerRegistry`：替换为远程 executor registry。
- `JobDispatcher`：替换为 broker dispatch 或 HTTP/gRPC dispatch。
- `Schedule`：增加 calendar、fixed-delay、one-shot、business-day。

