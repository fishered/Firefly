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
├── admin-web
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
        new AdminWebPlugin(),
        new PrometheusMetricsPlugin()
))) {
    plugins.start(context);
}
```

## Admin Web

模块：`plugins:admin-web`

默认不会随 `server` 自动加载。需要显式启用：

```powershell
.\gradlew.bat :server:run --args="--firefly.admin-web.enabled=true"
```

启用后默认监听：

```text
http://127.0.0.1:9710/
```

修改端口：

```powershell
.\gradlew.bat :server:run --args="--firefly.admin-web.enabled=true --firefly.admin-web.port=9810"
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
.\gradlew.bat :server:run --args="--firefly.metrics.prometheus.enabled=true"
```

启用后默认监听：

```text
http://127.0.0.1:9711/metrics
```

修改端口：

```powershell
.\gradlew.bat :server:run --args="--firefly.metrics.prometheus.enabled=true --firefly.metrics.prometheus.port=9811"
```

当前指标：

```text
firefly_plugin_up
firefly_jobs_total
firefly_jobs_enabled
firefly_nodes_online
firefly_next_fire_time_epoch_seconds
```

这些指标由插件根据 `FireflyPluginContext` 快照生成。调度核心不知道 Prometheus 的存在。

## 环境变量

也可以用环境变量启用：

```powershell
$env:FIREFLY_ADMIN_WEB_ENABLED="true"
$env:FIREFLY_METRICS_PROMETHEUS_ENABLED="true"
.\gradlew.bat :server:run
```

可用变量：

```text
FIREFLY_ADMIN_WEB_ENABLED
FIREFLY_ADMIN_WEB_PORT
FIREFLY_METRICS_PROMETHEUS_ENABLED
FIREFLY_METRICS_PROMETHEUS_PORT
```

## 后续方向

- plugin descriptor 和配置加载。
- server CLI 中按配置启用插件。
- Admin Web 增加任务启停、手动触发和下一次触发预览。
- Metrics 增加调度延迟、misfire、dispatch success/failure、executor 在线实例等指标。
- tracing 插件独立接入，不进入 core。
