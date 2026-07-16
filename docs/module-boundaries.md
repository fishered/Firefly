# Firefly 模块边界

Firefly 的模块边界要服务于两个目标：

- server 启动时足够轻量。
- 可选能力可以像组件或插件一样按需加载。

## Server

`server` 是运行时宿主，类似一个轻量 bootstrap 进程。

它负责：

- 解析启动配置。
- 初始化 Guice。
- 注册节点。
- 启动 scheduler engine。
- 根据配置加载插件。
- 维护生命周期和 shutdown hook。

它不应该直接承载所有协议和监控实现。否则 server 会越来越重。

## Plugins

`plugins/*` 放可选组件，例如：

- Admin HTTP
- Prometheus Metrics
- tracing
- audit
- alerting

插件必须显式启用。默认 server 不加载插件。

## Executors

`clients/*` 表达“业务执行器协议或 SDK”。

这里要区分两个方向：

```text
server-side gateway      调度中心接收业务执行器连接，并派发任务
business-side client     业务服务主动连接调度中心，并执行任务
```

当前 `transports/netty` 同时包含 gateway 和 client，是为了先打通远程执行器链路。后续更合理的拆分是：

```text
server
└── 内置 executor gateway 装配

executors
└── netty-client 或 netty-sdk

transports
└── netty-protocol
```

也就是说：**server 侧 gateway 可以成为 server 的内置模块，业务侧 executor client 不应该放进 server。**

这个方向更接近 Elasticsearch 的 bootstrap + modules/plugins 思路：宿主负责生命周期，模块提供能力，插件按需加载。

## Bootstrap 配置

server bootstrap 用 `firefly.node.mode` 表达单机或集群语义，用 `firefly.node.roles` 表达当前进程实际承担的职责。

```properties
firefly.node.mode=standalone
firefly.node.name=firefly-standalone
firefly.node.roles=api,gateway,scheduler
```

`mode` 是预设和校验边界，不直接等同于角色列表：

- `standalone`：允许使用 memory store，适合本地开发和单进程部署。
- `cluster`：必须使用共享存储，例如 JDBC；每个节点必须配置唯一的 `firefly.node.name`。

`roles` 才决定进程启动哪些职责：

```text
api        启动 Admin HTTP API
gateway    启动 Netty executor gateway
scheduler  启动 SchedulerEngine 调度循环
```

旧的 `firefly.admin-http.enabled`、`firefly.executor.gateway.netty.enabled` 和 `firefly.plugins=admin-http` 仍作为兼容入口保留；显式配置 `firefly.node.roles` 后，这些开关必须和角色一致。

后续拆分时，建议保留这个原则：

- `server` 负责装配 gateway 和生命周期。
- `clients/*` 提供业务侧 SDK/client。
- 共享协议再下沉到独立 transport/protocol 模块。
