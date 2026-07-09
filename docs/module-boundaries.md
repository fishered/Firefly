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

- Admin Web
- Prometheus Metrics
- tracing
- audit
- alerting

插件必须显式启用。默认 server 不加载插件。

## Executors

`executors/*` 表达“业务执行器协议或 SDK”。

这里要区分两个方向：

```text
server-side gateway      调度中心接收业务执行器连接，并派发任务
business-side client     业务服务主动连接调度中心，并执行任务
```

当前 `executors/netty` 同时包含 gateway 和 client，是为了先打通远程执行器链路。后续更合理的拆分是：

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
