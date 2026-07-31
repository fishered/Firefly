# Firefly 核心设计

Firefly 采用轻量调度核心、运行时宿主、可替换存储、独立通信模块和显式插件组成。模块边界首先服务于正确性和可运维性，其次才是复用。

## 模块边界

```text
libs/scheduler-core          纯 Java 调度领域与端口
stores/jdbc                 JDBC 持久化、事务、迁移与 HA 协调
transports/netty            Netty Gateway 与传输实现
clients/executor-netty      业务侧 Executor Client
apis/admin-model            Admin DTO / ViewModel
apis/admin-http             JDK HttpServer 管理 API
plugins/plugin-api          插件 SPI 与兼容契约
plugins/*                   按需加载的具体能力
server/runtime              Guice 装配和运行时协调
server/bootstrap            配置、启动、关闭和进程边界
server/launcher             main 入口与分发包
```

`libs/scheduler-core` 不依赖 Spring、Guice、HTTP、Netty 或具体数据库。外部能力通过端口接入，协议、存储和宿主不能反向进入领域模型。

Server 角色由 `firefly.node.roles` 决定：

- `api` 启动 Admin HTTP API。
- `gateway` 启动 Netty Executor Gateway。
- `scheduler` 启动调度循环和分片协调。

旧启用开关只作为兼容入口保留，不能形成第二套运行时角色模型。

## 调度不变量

1. Scheduler 只处理当前节点持有且租约有效的 shard。
2. 到期任务先在同一事务中完成 lease/fencing 校验、游标 CAS、execution 和 Outbox 写入。
3. 派发发生在事务提交后；临时失败由 Outbox 重投，不能回滚已经推进的调度事实。
4. execution 和 target 只能沿单向状态机进入终态，迟到 ACK 或结果不能覆盖终态。
5. 所有集群协调时间使用数据库校准时钟，业务时区只参与 Schedule 计算。

内存模式使用 `nextFireTime` 有序索引。JDBC 集群模式使用本地 `SchedulerTimingIndex` 加轻量 revision 刷新，避免每次 tick 扫描数据库任务表。

详细任务、执行器和执行状态模型见 [scheduler-center.md](scheduler-center.md)，租约和 fencing 见 [ha-cluster.md](ha-cluster.md)。

## 通信边界

```text
Admin browser/client --HTTP--> JDK HttpServer Admin API
Executor client       --Netty-> Gateway
Gateway               --Netty-> peer Gateway / Executor
HTTP integration      --HTTP--> OkHttp client boundary
```

- JDK `HttpServer` 负责管理面 HTTP 边界，内部可以拆分 Router、Filter 和 Controller，但不引入大型 Web 框架。
- Netty 负责长连接、高频派发、ACK、结果和 Gateway 转发。
- OkHttp 负责确实需要 HTTP 语义的客户端调用。
- 协议模型与编解码应独立于 Gateway 生命周期和业务侧 Client，以便单独做兼容测试。

## 扩展原则

- Store、Transport、Plugin 和 Schedule 通过明确端口扩展。
- 使用设计模式或 DDD 时，以减少条件分支、隔离变化和保护不变量为判断标准，不追求形式上的层数。
- 线程池、队列和重试必须有容量与终止边界，不能在 EventLoop 或调度 timer 中执行阻塞工作。
- 新公共协议、配置和数据库结构需要兼容策略和升级路径。

开发与提交规则见 [development.md](development.md)，1.0.2 的架构增量见 [v1.0.2-roadmap.md](v1.0.2-roadmap.md)。

