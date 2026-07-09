# Firefly 集成方案

Firefly 的集成层分成三类入口：传统 Java 项目、Spring Boot 项目、独立 server CLI。核心原则是：调度核心保持纯 Java，框架适配放在独立模块里。

页面操作、指标监控、审计告警这类能力通过 `plugins/*` 接入，不写进调度核心。

## 模块

```text
integrations
├── embedded               # 传统 Java / 非 Spring 项目的嵌入式门面
├── netty-spring-boot-starter
├── spring-boot-starter    # Spring Boot 自动装配
└── server-cli             # 独立 server 命令入口占位
```

```text
plugins
├── plugin-api             # 插件 SPI
├── admin-web              # 运维页面和 JSON 接口
└── metrics-prometheus     # Prometheus 文本指标
```

## 传统项目快速集成

适用于 Servlet 老项目、Guice 项目、命令行服务、内部 worker 服务等不使用 Spring Boot 的场景。

```java
try (FireflyScheduler scheduler = FireflyScheduler.create()) {
    JobDefinition job = JobDefinition.builder()
            .id("daily-report")
            .name("Daily Report")
            .handlerName("reportHandler")
            .schedule(new CronSchedule("0 0 9 * * *"))
            .zoneId(ZoneId.of("Asia/Shanghai"))
            .build();

    scheduler.register(FireflyJobRegistration.of(job, context -> {
        // run your task here
    }));

    scheduler.start();
}
```

可以通过 `FireflyOptions` 调整线程数、线程名前缀和时钟：

```java
FireflyScheduler scheduler = FireflyScheduler.create(FireflyOptions.builder()
        .workerThreads(4)
        .workerThreadNamePrefix("biz-scheduler")
        .build());
```

## Spring Boot Starter

Spring Boot 项目引入 starter 后，会自动创建 `FireflyScheduler`。业务侧只需要提供 `FireflyJobRegistration` Bean。

```java
@Bean
FireflyJobRegistration reportJob() {
    JobDefinition job = JobDefinition.builder()
            .id("spring-report")
            .name("Spring Report")
            .handlerName("reportHandler")
            .schedule(new CronSchedule("0 0 9 * * *"))
            .zoneId(ZoneId.of("Asia/Shanghai"))
            .build();

    return FireflyJobRegistration.of(job, context -> {
        // run your task here
    });
}
```

配置项：

```yaml
firefly:
  enabled: true
  auto-start: true
  worker-threads: 4
  worker-thread-name-prefix: firefly-worker
```

## Server CLI

`integrations/server-cli` 当前只保留独立 server 命令入口。它的目标是后续承载配置文件加载、HTTP 管理 API、独立进程运行等能力。

这个入口先保持很薄，避免在核心能力稳定前过早引入复杂运行时。

当前 `server` 模块使用 `FireflyBootstrap` 管理启动流程。默认只启动调度服务，不自动注册 demo 任务，也不加载 Admin Web 或 Prometheus Metrics。

启用 demo：

```powershell
.\gradlew.bat :server:run --args="--firefly.demo.enabled=true"
```

## 可选插件

插件由宿主服务显式启用。示例：

```java
FireflyPluginContext context = FireflyPluginContext.builder()
        .jobRepository(jobRepository)
        .nodeRegistry(nodeRegistry)
        .build();

try (FireflyPluginManager plugins = new FireflyPluginManager(List.of(
        new AdminWebPlugin(),
        new PrometheusMetricsPlugin()
))) {
    plugins.start(context);
}
```

详细说明见 [plugins.md](plugins.md)。

## 远程执行器集成

当业务服务不想把调度核心嵌入进程，或者希望统一由调度中心管理任务配置时，推荐使用 Netty 远程执行器：

```text
业务服务主动连接 -> scheduler gateway
调度中心触发任务 -> gateway 按 executorName 路由到在线实例
业务服务执行任务 -> ACK / REPORT_RESULT 返回结果
```

传统项目使用 `executors:netty`：

```java
NettyExecutorClient client = NettyExecutorClient.builder()
        .schedulerHost("127.0.0.1")
        .schedulerPort(9700)
        .executorName("billing-executor")
        .serviceName("billing-service")
        .build()
        .registerHandler("billingHandler", context -> {
            // run business code
        });

client.start();
```

Spring Boot 项目使用 `integrations:netty-spring-boot-starter`：

```yaml
firefly:
  executor:
    netty:
      enabled: true
      auto-start: true
      scheduler-host: 127.0.0.1
      scheduler-port: 9700
      executor-name: billing-executor
      service-name: billing-service
```

业务代码只声明 handler Bean：

```java
@Bean
NettyJobHandlerRegistration billingHandler() {
    return NettyJobHandlerRegistration.of("billingHandler", context -> {
        // run business code
    });
}
```

业务服务不需要开放监听端口。它只需要能连到调度中心 gateway。
