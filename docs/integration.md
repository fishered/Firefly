# Firefly 集成方案

Firefly 的集成层分成三类入口：传统 Java 项目、Spring Boot 项目、独立 server CLI。核心原则是：调度核心保持纯 Java，框架适配放在独立模块里。

页面操作、指标监控、审计告警这类能力通过 `plugins/*` 接入，不写进调度核心。

## 模块

```text
integrations
├── embedded               # 传统 Java / 非 Spring 项目的嵌入式门面
├── firefly-spring-boot-starter
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

Spring Boot 项目只需要使用 `com.firefly:firefly-spring-boot-starter`，配置 `firefly.executor.name` 后即可自动注册：

启动时会依次输出自动配置激活、Gateway 连接成功和 Executor 注册成功日志。只有收到 Gateway 的 `REGISTERED` 响应后，才表示该实例已经可以接收任务；单纯 Spring Context 启动成功不能替代这个判断。

Maven 依赖：

```xml
<dependency>
    <groupId>com.firefly</groupId>
    <artifactId>firefly-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
firefly:
  executor:
    name: billing-executor
    # Kubernetes 建议使用 Pod 名；本地多实例可使用服务名加端口。
    instance-id: ${HOSTNAME:billing-service-local}
    auto-start: true
    gateway-addresses:
      - firefly-1:9700
      - firefly-2:9700
      - firefly-3:9700
    service-name: billing-service
    reconnect-initial-delay: 1s
    reconnect-max-delay: 30s
    auth-token: ${FIREFLY_EXECUTOR_AUTH_TOKEN:}
    idempotency-directory: /data/firefly-executor-results
    idempotency-retention: 24h
    job-registration:
      enabled: true
      admin-url: http://firefly-api:9710
      admin-token: ${FIREFLY_ADMIN_TOKEN:}
      update-existing: false
      fail-fast: false
```

推荐直接在 Spring Bean 的业务方法上声明任务，不需要为每个 Handler 或任务编写 `@Bean`：

```java
import com.firefly.domain.ExecutionContext;
import com.firefly.spring.annotation.FireflyJob;
import org.springframework.stereotype.Component;

@Component
public class BillingJobs {
    @FireflyJob(
            id = "billing-daily-job",
            name = "每日账单处理",
            cron = "0 0 2 * * *",
            zoneId = "Asia/Shanghai",
            parameters = "tenant=primary"
    )
    public void billingHandler(ExecutionContext context) {
        // run business code
        System.out.println("executionId=" + context.executionId());
    }
}
```

`handlerName` 默认使用方法名，因此上例自动注册为 `billingHandler`。方法必须返回 `void`，参数只能为空，
或接收一个 `ExecutionContext`。Starter 会先完成全部注解扫描和 Handler 注册，再连接 Gateway；Spring
应用就绪后通过 Admin API 同步任务。Executor 名称自动使用 `firefly.executor.name`。

同一个业务方法需要多个调度计划时，可以重复使用注解，不需要复制 Handler：

```java
@FireflyJob(id = "billing-incremental", cron = "0 */5 * * * *", zoneId = "Asia/Shanghai")
@FireflyJob(id = "billing-full", cron = "0 0 3 * * *", zoneId = "Asia/Shanghai")
public void reconcile(ExecutionContext context) {
    // distinguish jobs with context.jobId() when necessary
}
```

`FireflyJobRegistration` Bean API 仍保留给需要根据配置或租户列表动态生成任务声明的高级场景，普通业务
集成应优先使用 `@FireflyJob`。

同步时 Starter 通过 `admin-url` 查询 `jobId`。任务不存在时创建；已存在且
`update-existing=false` 时保留控制台配置；只有显式设置 `update-existing=true` 才按代码声明更新。
多个服务实例同时启动时，Admin API 通过创建冲突检测保证最终只有一个任务定义。

如果 Admin API 开启 RBAC，首次创建任务使用的 `admin-token` 必须是 `ADMIN`；持续更新只要求
`OPERATOR`。默认 `fail-fast=false`，调度中心短暂不可用时业务服务仍可启动，但日志会明确报告同步失败。
显式指定的 `handlerName` 必须在当前服务内唯一；重复 `jobId`、重复 Handler 名或错误的方法签名会使
Spring 启动明确失败。

一个 Handler 可以对应多个启动任务声明，例如分别声明“每 5 分钟增量处理”和“每天全量处理”。
这些任务拥有独立的 `jobId`、Cron、路由策略和执行记录。

业务服务不需要开放监听端口。它只需要能连到调度中心 gateway。

### 实例下线与重启

Spring Boot 默认注册 JVM shutdown hook。正常停止 ApplicationContext、IDE Stop、容器 `SIGTERM` 时，
Starter 会发送 `UNREGISTER_EXECUTOR`，等待 Netty Channel 关闭，再释放线程组；Gateway 会立即把该
`instanceId + sessionId` 标记离线并删除共享位置租约。

`kill -9`、机器掉电和网络分区无法执行任何 JVM hook。Gateway 本地状态在最后心跳超过 30 秒后显示
为离线；跨 Gateway 选址还受 `instance-location-lease` 约束，默认最长 90 秒后排除。窗口内如果投递到
失效连接，未收到 ACK 的 Outbox 会超时重投，不会记录为成功。

`instance-id` 默认是启动时生成的 UUID，因此重启后页面可能同时看到一个新在线实例和一个旧离线记录，
但“已绑定实例”只统计在线实例。生产环境建议用 Pod 名、容器实例名或服务注册实例 ID 显式配置稳定且
副本唯一的值。同一 `instance-id` 重连时，新 `sessionId` 会替换旧会话，旧连接的迟到断开不会覆盖新会话。

客户端会同时连接所有 `gateway-addresses`，某个 Gateway 暂时不可用时按指数退避自动重连。较大集群还可启用共享实例位置目录与 Gateway 内部转发，领取 Outbox 的节点会按实例的 `gatewayNodeId + sessionId` 将目标帧转发到持有连接的 Gateway。

广播和分片的 `retryScope` 默认是 `FAILED_TARGETS_ONLY`；选择 `ALL_TARGETS` 时，每个 attempt 会重跑全部原目标。`QUORUM` 在默认范围下会结转上一 attempt 的成功目标。这依赖业务服务正确处理 `rootExecutionId`、分片键和业务幂等键。

执行排障时可以通过 `GET /api/executions/{executionId}` 查看父 execution 和 target 明细，包括 `instanceId`、`gatewayNodeId`、ACK/完成时间、错误信息以及 QUORUM retry 产生的 `carried=true` 结转目标。

运行中的 execution 可通过 `POST /api/executions/{executionId}/cancel` 协作式终止，请求体可携带 `reason`。调度侧会终止未完成 target 和 Outbox，Gateway 向在线 Executor 发送 `CANCEL_JOB`；业务 Handler 应响应线程中断，并且不能把该能力理解为进程级强杀。

Outbox 达到最大重投次数后会进入 `DEAD`。运维侧可以用 `GET /api/outbox/dead` 查看死信，并用 `POST /api/outbox/{outboxId}/requeue` 手动重放；重放需要 `OPERATOR` 或 `ADMIN` Token，只会把仍处于 `DEAD` 的记录改回 `RETRY`。

`firefly.dispatch.outbox.max-attempts` 是真实投递次数上限，既覆盖 Gateway 无法接受发送，也覆盖已经发送但 Executor 未在 deadline 前 ACK。达到上限后不会再下发；下一次认领只执行 fenced `CLAIMED -> DEAD`。`firefly_dispatch_outbox_delivery_exhausted_total` 会累计这类事件，即使随后人工重放也不会回退。

每次 Outbox 认领都会递增 `attempt` 并记录 `claim_owner`。Gateway/Scheduler 完成发送或安排重试时必须同时匹配这两个值；认领过期后，旧节点的迟到回写会被拒绝，不能覆盖新 owner 或已 ACK 的状态。传输暂时不可用只触发 Outbox 退避重投，不会提前把 execution 固化为业务失败。

Prometheus 可用 `firefly_jobs_due_total` 和 `firefly_jobs_overdue_max_seconds` 观察调度积压及最严重逾期，结合 `firefly_schedule_delay_seconds`、`firefly_dispatch_outbox_oldest_age_seconds` 可以区分“Scheduler 未及时生成派发”与“派发链路积压”。

`firefly_scheduler_shard_due_jobs{shard="..."}` 用于识别热点分片；Executor 侧还提供活跃连接、注册拒绝和断线累计指标。标准告警规则位于 `config/prometheus/firefly-alerts.yml`。

`idempotency-directory` 启用文件型 `ExecutorResultStore`，用于在容器重启后重放已经完成的目标结果；目录应挂载持久卷。它不覆盖业务完成但结果尚未落盘的崩溃窗口，Handler 仍需业务幂等。也可以声明自定义 `ExecutorResultStore` Bean 接入数据库、Redis 或业务侧幂等存储。

生产业务可使用 `clients:executor-netty` 中的 `JdbcBusinessIdempotencyStore`。Spring Boot 配置 `firefly.executor.business-idempotency.enabled=true` 且存在 `DataSource` 时会自动创建 `BusinessIdempotencyStore` Bean；非 Spring 项目直接构造该 Store 并传给 `registerIdempotentHandler`。Store 的 claim token 可阻止过期旧 owner 覆盖接管后的状态，但业务表唯一约束仍是最终副作用幂等边界。

远程任务支持三种分发模式：

```text
UNICAST    选择一个在线实例，只执行一份
BROADCAST  对在线实例快照逐个生成子执行
SHARDING   按 shardCount 生成子执行并分配给实例
```

`routingStrategy` 支持 `ROUND_ROBIN`、`RANDOM` 和 `CONSISTENT_HASH`。广播和分片子执行具有独立 `executionId`，并保留共同的 `parentExecutionId`。

## 实现进度

当前实现进度统一维护在 [implementation-progress.md](implementation-progress.md)。
