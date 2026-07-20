# Firefly 插件体系

Firefly 的插件体系用于承载可选组件，例如页面操作、指标监控、审计、告警和后续的扩展协议。

核心原则：

- `libs/scheduler-core` 不依赖插件、HTTP 页面、Prometheus 或具体监控实现。
- `plugins/plugin-api` 只定义插件生命周期和上下文。
- 具体组件放在 `plugins/*`，由 server 或宿主应用决定是否启用。
- `server` 默认不加载任何插件，插件必须显式开启。

## 模块

```text
plugins
├── plugin-api
├── admin-http
└── metrics-prometheus
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

启动插件：

```java
try (FireflyPluginManager plugins = new FireflyPluginManager(List.of(
        new AdminHttpPlugin(),
        new PrometheusMetricsPlugin()
))) {
    plugins.start(context);
}
```

## Admin HTTP API

模块：`apis:admin-http`

默认不会随 `server` 自动加载。需要显式启用：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.admin-http.enabled=true"
```

也可以通过插件列表启用：

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

- plugin descriptor 和配置加载。
- server CLI 中按配置启用插件。
- Admin HTTP API 增加任务启停、手动触发和下一次触发预览。
- Metrics 增加调度延迟、misfire、dispatch success/failure、executor 在线实例等指标。
- tracing 插件独立接入，不进入 core。

## Admin HTTP API 与 Admin UI 约定

Admin HTTP API 插件只提供 JSON 管理接口，不再承载完整页面。Admin UI 作为独立 Node 服务放在 `ui/admin`，启动后将浏览器侧的 `/api/*` 请求代理到 Java Admin HTTP API。

当前 UI 页面：

```text
/           总览页面
/jobs       任务管理页面
/executors  执行器页面
/nodes      节点与集群页面
```

当前 JSON 接口：

```text
/api/health
/api/overview
/api/jobs
/api/executors
/api/nodes
```

Node UI 默认监听 `127.0.0.1:9720`，默认代理目标为 `http://127.0.0.1:9710`。可以通过 `FIREFLY_ADMIN_UI_HOST`、`FIREFLY_ADMIN_UI_PORT`、`FIREFLY_ADMIN_API` 和 `FIREFLY_ADMIN_API_TIMEOUT_MS` 调整。

Java 代码只负责插件生命周期和 JSON API，不应内嵌完整 HTML 页面，也不应引入前端依赖。

## 模块拆分方向

Admin HTTP API 不再作为长期插件形态扩展。目标是：

```text
apis/admin-model   Admin DTO 和 ViewModel
apis/admin-http    Admin HTTP API
ui/admin           Node 前端工程
```

`apis/admin-http` 和 `ui/admin` 是长期分离的 API/UI 模块：新增管理接口进入 `apis/admin-http`，前端页面与 Node 服务进入 `ui/admin`。Prometheus 继续保留在 `plugins/metrics-prometheus`，因为它是可选观测插件。
