# Firefly 插件体系

Firefly 的插件体系用于承载可选组件，例如页面操作、指标监控、审计、告警和后续的扩展协议。

核心原则：

- `libs/scheduler-core` 不依赖插件、HTTP 页面、Prometheus 或具体监控实现。
- `plugins/plugin-api` 只定义插件生命周期、只读配置和发现机制。
- 具体组件放在 `plugins/*`，由 server 或宿主应用决定是否启用。
- 可选插件必须通过 `firefly.plugins` 显式开启；API 节点所需的 Admin HTTP 由 `api` 角色自动装配。
- 外部插件与 server 运行在同一 JVM，属于可信代码，不是安全沙箱。

## 模块

```text
plugins
├── plugin-api
└── metrics-prometheus

apis
└── admin-http
```

## Plugin API

插件实现 `FireflyPlugin`：

```java
public interface FireflyPlugin extends AutoCloseable {
    String id();

    void start(FireflyPluginContext context);
}
```

插件通过 `FireflyPluginContext` 读取可选运行时能力：

```java
FireflyPluginContext context = FireflyPluginContext.builder()
        .jobRepository(jobRepository)
        .nodeRegistry(nodeRegistry)
        .executorRegistry(executorRegistry)
        .build();
```

插件自己的配置使用稳定前缀读取：

```java
String endpoint = context.configuration()
        .pluginProperty(id(), "endpoint")
        .orElseThrow();
```

对应配置文件和环境变量为：

```properties
firefly.plugin.acme-alerts.endpoint=http://127.0.0.1:9900
```

```text
FIREFLY_PLUGIN_ACME_ALERTS_ENDPOINT=http://127.0.0.1:9900
```

启动插件：

```java
try (FireflyPluginManager plugins = new FireflyPluginManager(List.of(
        new AdminHttpPlugin(),
        new PrometheusMetricsPlugin()
))) {
    plugins.start(context);
}
```

## 外部 JAR 插件

外部插件只需要依赖 `com.firefly:plugin-api:0.1.0-SNAPSHOT`，实现 `FireflyPlugin`，并在 JAR 中增加 JDK SPI 描述文件：

```text
META-INF/services/com.firefly.plugin.FireflyPlugin
```

文件内容是实现类全名，例如：

```text
com.acme.firefly.AcmeAlertsPlugin
```

默认只需要将插件 JAR 及其依赖 JAR 放到项目根目录的 `plugins` 目录，再按插件 `id()` 启用：

```properties
firefly.plugins=metrics-prometheus,acme-alerts
```

只有不使用默认 `plugins` 目录时才需要覆盖目录：

```properties
firefly.plugins.directory=/opt/firefly/extensions
```

`firefly.plugin.<id>.*` 不是 Firefly 强制配置，而是留给具体插件的可选参数。例如某个告警插件要求配置
接收地址时，才由该插件声明并读取：

```properties
firefly.plugin.acme-alerts.endpoint=http://alerts.internal:9900
```

启动时 Firefly 会同时扫描运行时 classpath 和该目录中的 JAR。配置了但未发现的插件、重复插件 ID、
SPI 实例化失败都会阻止服务启动，避免节点带着不完整能力加入集群。已启动插件会在服务关闭时按实际启动
顺序反向关闭；当前不支持运行中热替换，升级外部插件需要滚动重启节点。

外部目录中的 JAR 共用一个子 ClassLoader，便于插件携带第三方依赖。不同插件不要携带同一依赖的互不兼容
版本；需要强隔离时，应将插件作为独立 sidecar/API 服务，而不是放入调度进程。

## 运行时状态与管理页面

`FireflyPluginManager` 会维护当前节点的插件描述和生命周期状态。插件可以覆盖 `displayName()`、`version()`
和 `description()` 提供展示信息；运行时还会补充实现类、`CLASSPATH`/`EXTERNAL` 来源以及
`LOADED`、`ACTIVE`、`STOPPED` 状态。

API 节点通过 `GET /api/plugins` 返回该快照。Admin UI 的“插件”页面展示加载数、运行数、外部插件数和
插件注册表。该页面只读：插件启停属于节点启动事务的一部分，不能在页面中单独绕过 Bootstrap 生命周期。
配置变化或插件升级通过滚动重启节点生效。

## Executor Transport 与 Netty

Netty 是 Firefly 默认且随发行版提供的 Executor 长连接传输，不作为普通可选插件处理。Gateway 节点默认需要它，
否则远程 Executor 无法注册和接收任务。但调度与节点排空只依赖 `RemoteExecutorTransport`，不依赖 Netty 类型；
因此未来增加 HTTP/2 或 gRPC 实现时可以替换传输层，而不修改调度核心、Outbox 或 Admin 插件。

这个边界有意区分两类扩展：`FireflyPlugin` 扩展可选产品能力，`RemoteExecutorTransport` 扩展执行器通讯机制。
当前发行版只装配 Netty 实现，不承诺在同一 Gateway 节点同时运行多个 Executor transport。

## Admin HTTP API

模块：`apis:admin-http`

Admin HTTP 是 `api` 节点角色的内置能力。显式配置节点角色时使用：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.node.roles=api"
```

未显式配置节点角色时，也可以使用兼容插件开关启用：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.plugins=admin-http"
```

启用后默认监听：

```text
http://127.0.0.1:9710/
```

修改端口：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.admin-http.enabled=true --firefly.admin-http.port=9810"
```

当前端点：

```text
/             运维页面
/api/health   健康检查
/api/jobs     任务列表 JSON
/api/nodes    在线节点 JSON
```

这个插件使用 JDK `HttpServer`，不引入 Spring 或其他 Web 框架。

## Prometheus Metrics

模块：`plugins:metrics-prometheus`

默认不会随 `server` 自动加载。需要显式启用：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.metrics.prometheus.enabled=true"
```

也可以通过插件列表启用：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.plugins=metrics-prometheus"
```

启用后默认监听：

```text
http://127.0.0.1:9711/metrics
```

修改端口：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.metrics.prometheus.enabled=true --firefly.metrics.prometheus.port=9811"
```

当前指标：

```text
firefly_plugin_up
firefly_jobs_total
firefly_jobs_enabled
firefly_nodes_online
firefly_next_fire_time_epoch_seconds
firefly_jobs_due_total
firefly_jobs_overdue_max_seconds
firefly_scheduler_shard_due_jobs
firefly_schedule_delay_seconds
firefly_outbox_claim_age_seconds
firefly_executor_ack_delay_seconds
firefly_execution_duration_seconds
firefly_dispatch_outbox_oldest_age_seconds
firefly_shard_lease_renewal_failures_total
firefly_scheduler_due_backlog_events_total
firefly_dispatch_outbox_delivery_exhausted_total
firefly_executor_connections
firefly_executor_registration_rejections_total
firefly_executor_disconnects_total
firefly_scheduler_owned_shards
firefly_database_clock_offset_milliseconds
firefly_database_clock_drift_warnings_total
firefly_database_clock_sync_failures_total
```

这些指标由插件根据 `FireflyPluginContext` 快照生成。调度核心不知道 Prometheus 的存在。

仓库内的 `config/prometheus/firefly-alerts.yml` 提供 p99 调度延迟、任务逾期、热点分片、Outbox 停滞、lease 失败、数据库时钟漂移和 Executor 注册异常告警。

## 环境变量

也可以用环境变量启用：

```powershell
$env:FIREFLY_ADMIN_HTTP_ENABLED="true"
$env:FIREFLY_METRICS_PROMETHEUS_ENABLED="true"
.\gradlew.bat :server:launcher:run
```

可用变量：

```text
FIREFLY_ADMIN_HTTP_ENABLED
FIREFLY_ADMIN_HTTP_PORT
FIREFLY_PLUGINS
FIREFLY_METRICS_PROMETHEUS_ENABLED
FIREFLY_METRICS_PROMETHEUS_PORT
```

## 后续方向

- tracing 插件独立接入，不进入 core。
- 为外部插件补充兼容性声明和滚动升级测试矩阵。

## Admin HTTP API 与 Admin UI 约定

Admin HTTP API 插件只提供 JSON 管理接口，不再承载完整页面。Admin UI 作为独立 Node 服务放在 `ui/admin`，启动后将浏览器侧的 `/api/*` 请求代理到 Java Admin HTTP API。

当前 UI 页面：

```text
总览
任务
执行器
执行记录
节点与集群
插件
配置
```

当前 JSON 接口：

```text
/api/health
/api/overview
/api/jobs
/api/executors
/api/nodes
/api/plugins
```

Node UI 默认监听 `127.0.0.1:9720`，默认代理目标为 `http://127.0.0.1:9710`。可以通过 `FIREFLY_ADMIN_UI_HOST`、`FIREFLY_ADMIN_UI_PORT`、`FIREFLY_ADMIN_API` 和 `FIREFLY_ADMIN_API_TIMEOUT_MS` 调整。

前端支持中文和英文切换，并按当前页面加载接口数据。Node 服务缓存静态资源、返回 ETag，并以流式方式代理
Admin API 响应，降低重复页面切换和较大响应带来的等待。

Java 代码只负责插件生命周期和 JSON API，不应内嵌完整 HTML 页面，也不应引入前端依赖。

## 模块拆分方向

Admin HTTP API 不再作为长期插件形态扩展。目标是：

```text
apis/admin-model   Admin DTO 和 ViewModel
apis/admin-http    Admin HTTP API
ui/admin           Node 前端工程
```

`apis/admin-http` 和 `ui/admin` 是长期分离的 API/UI 模块：新增管理接口进入 `apis/admin-http`，前端页面与 Node 服务进入 `ui/admin`。Prometheus 继续保留在 `plugins/metrics-prometheus`，因为它是可选观测插件。
