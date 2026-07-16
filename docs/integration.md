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
├── admin-http              # 管理 HTTP API
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

当前 `server` 模块使用 `FireflyBootstrap` 管理启动流程。在项目根目录启动时会自动加载 `config/firefly-server.properties`，当前默认 profile 为 `pg`，会启用 Admin HTTP、Prometheus Metrics、Netty executor gateway 和 PostgreSQL 持久化。demo 任务默认关闭。

当前节点职责由 `firefly.node.roles` 指定：

```properties
firefly.node.mode=standalone
firefly.node.name=firefly-standalone
firefly.node.roles=api,gateway,scheduler
```

`standalone` 可以使用 memory、H2 或 PostgreSQL；`cluster` 必须使用 JDBC 共享存储，并且每个节点必须配置唯一的 `firefly.node.name`。

启用 demo：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.demo.enabled=true"
```

切换存储 profile：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=h2"
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=memory"
```

主配置在 `config/firefly-server.properties`，存储差异配置在 `config/profiles/*.properties`。

## 可选插件

可选能力由宿主服务显式启用。Admin HTTP API 位于 `apis/admin-http`，Prometheus 指标位于 `plugins/metrics-prometheus`。示例：

```java
FireflyPluginContext context = FireflyPluginContext.builder()
        .jobRepository(jobRepository)
        .nodeRegistry(nodeRegistry)
        .build();

try (FireflyPluginManager plugins = new FireflyPluginManager(List.of(
        new AdminHttpPlugin(),
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

传统项目使用 `transports:netty`：

```java
NettyExecutorClient client = NettyExecutorClient.builder()
        .gatewayAddresses(List.of(
                "firefly-1:9700",
                "firefly-2:9700",
                "firefly-3:9700"
        ))
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
      gateway-addresses:
        - firefly-1:9700
        - firefly-2:9700
        - firefly-3:9700
      executor-name: billing-executor
      service-name: billing-service
      reconnect-initial-delay: 1s
      reconnect-max-delay: 30s
      auth-token: ${FIREFLY_EXECUTOR_AUTH_TOKEN:}
      idempotency-directory: /data/firefly-executor-results
      idempotency-retention: 24h
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

客户端会同时连接所有 `gateway-addresses`，某个 Gateway 暂时不可用时按指数退避自动重连。高可用部署应让每个带 `scheduler` 职责的节点同时带 `gateway` 职责，使任一赢得任务游标 CAS 的 Scheduler 都能通过自己的本地 Gateway 下发任务。

`ALL_SUCCESS`/`ANY_SUCCESS` 广播和分片重试只重新派发失败、超时或缺失目标；`QUORUM` 会把上一 attempt 已成功目标结转为 synthetic carry target，只重新派发未成功目标，并按跨 attempt 成功数判断法定多数。这依赖业务服务正确处理 `rootExecutionId` 和业务幂等键。

执行排障时可以通过 `GET /api/executions/{executionId}` 查看父 execution 和 target 明细，包括 `instanceId`、`gatewayNodeId`、ACK/完成时间、错误信息以及 QUORUM retry 产生的 `carried=true` 结转目标。

Outbox 达到最大重投次数后会进入 `DEAD`。运维侧可以用 `GET /api/outbox/dead` 查看死信，并用 `POST /api/outbox/{outboxId}/requeue` 手动重放；重放需要 `OPERATOR` 或 `ADMIN` Token，只会把仍处于 `DEAD` 的记录改回 `RETRY`。

`idempotency-directory` 启用文件型 `ExecutorResultStore`，用于在容器重启后重放已经完成的目标结果；目录应挂载持久卷。它不覆盖业务完成但结果尚未落盘的崩溃窗口，Handler 仍需业务幂等。也可以声明自定义 `ExecutorResultStore` Bean 接入数据库、Redis 或业务侧幂等存储。

远程任务支持三种分发模式：

```text
UNICAST    选择一个在线实例，只执行一份
BROADCAST  对在线实例快照逐个生成子执行
SHARDING   按 shardCount 生成子执行并分配给实例
```

`routingStrategy` 支持 `ROUND_ROBIN`、`RANDOM` 和 `CONSISTENT_HASH`。广播和分片子执行具有独立 `executionId`，并保留共同的 `parentExecutionId`。

## 实现进度

当前实现进度统一维护在 [implementation-progress.md](implementation-progress.md)。
