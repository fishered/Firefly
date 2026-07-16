# Firefly

[English](README_EN.md)

Firefly 是一个基于 Java 21 的轻量级定时调度服务。

萤火虫会在黑暗中带来星点光亮与希望。Firefly 也希望做类似的事情：在正确的时间点亮正确的任务，让调度系统保持轻量、清晰、可靠，并为后续的分布式能力留下自然的生长空间。

## 项目定位

Firefly 关注三个核心目标：

- **时区正确**：任务显式声明 IANA `ZoneId`，cron 按任务本地时间计算，运行态游标统一使用 UTC `Instant`。
- **核心轻量**：`scheduler-core` 保持纯 Java，不依赖 Spring、Guice、HTTP、数据库或特定运行时。
- **易于扩展**：存储、执行器协议、管理 API、集群能力都可以作为独立模块逐步演进。

## 核心能力

- Java 21
- Gradle 多模块构建
- Gradle Wrapper 固定构建环境
- `libs/scheduler-core` 纯 Java 调度核心
- `server` 模块使用 Guice 做对象装配
- 传统 Java 项目的嵌入式集成门面
- Spring Boot Starter 自动装配入口
- Netty 长连接远程执行器基础实现
- JDBC 持久化存储：任务定义、nextFireTime CAS、节点注册、心跳、shard lease、fencing token
- 插件 SPI：可选组件通过独立插件接入，不硬编码到调度核心
- Admin HTTP API 与独立 Node Admin UI：管理接口和运维页面分离
- Prometheus Metrics 插件：独立 `/metrics` 文本指标端点
- Server CLI 占位模块
- 内存版任务仓库
- 任务级 IANA 时区支持
- 6 位 cron：秒 分 时 日 月 周
- fixed-rate 调度
- misfire 策略：`SKIP`、`FIRE_ONCE`、`CATCH_UP`
- 并发策略：`ALLOW`、`FORBID`
- 本地任务处理器注册表
- 单元测试覆盖 cron、时区、DST、仓库 CAS、misfire 行为和 Guice 装配

## 项目结构

```text
firefly
├── libs
│   └── scheduler-core     # 纯调度核心，不依赖 Guice/Spring
├── server                 # 启动入口和 Guice 装配
├── integrations
│   ├── embedded           # 传统 Java / 非 Spring 集成
│   ├── netty-spring-boot-starter
│   ├── spring-boot-starter
│   └── server-cli
├── executors
│   └── netty              # 远程执行器长连接实现
├── stores
│   └── jdbc               # JDBC 任务仓库和 HA 协调存储
├── plugins
│   ├── plugin-api         # 插件 SPI 和生命周期管理
│   └── metrics-prometheus # Prometheus 文本指标插件
├── docs
│   ├── deployment.md      # 镜像构建和容器部署
│   ├── integration.md     # 集成方案
│   ├── ha-cluster.md
│   ├── netty-executor.md
│   ├── plugins.md
│   ├── scheduler-center.md
│   └── timezone.md        # 时区和 DST 语义
├── skills                 # 项目专属协作规则
├── gradle/wrapper
├── build.gradle
├── settings.gradle
├── gradlew
└── gradlew.bat
```

推荐按能力边界继续扩展目录：

```text
transports/http
apis/admin-http
plugins/xxx
```

## 快速开始

环境要求：

- JDK 21

运行测试：

```bash
./gradlew test
```

Windows：

```powershell
.\gradlew.bat test
```

运行 demo server：

```bash
./gradlew :server:launcher:run
```

Windows：

```powershell
.\gradlew.bat :server:launcher:run
```

在项目根目录启动时会自动加载 `config/firefly-server.properties`。当前默认 profile 是 `pg`，会启用 Admin HTTP、Prometheus Metrics、Netty executor gateway，并使用本地 PostgreSQL 持久化；demo 任务仍然默认关闭。

节点职责由 `firefly.node.roles` 指定。默认配置是单进程全角色：

```properties
firefly.node.mode=standalone
firefly.node.name=firefly-standalone
firefly.node.roles=api,gateway,scheduler
```

`cluster` 模式必须使用 JDBC 共享存储，并且每个节点需要唯一的 `firefly.node.name`。

启用 5 秒 demo 任务：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.demo.enabled=true"
```

切换到 H2 本地文件存储：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=h2"
```

切换到内存存储：

```powershell
.\gradlew.bat :server:launcher:run --args="--firefly.config.profile=memory"
```

配置主文件位于 `config/firefly-server.properties`，差异化配置位于 `config/profiles/*.properties`。命令行参数和环境变量会覆盖配置文件中的值。

## 集成方式

- 传统 Java 项目：使用 `integrations:embedded`，通过 `FireflyScheduler.create()` 嵌入。
- Spring Boot 项目：使用 `integrations:spring-boot-starter`，声明 `FireflyJobRegistration` Bean。
- 远程业务执行器：使用 `transports:netty`，业务服务主动连接调度中心 gateway，不需要业务侧开放监听端口。
- 独立 server：`integrations:server-cli` 已保留入口，后续承载配置文件加载和独立进程运行。

详细说明见 [docs/integration.md](docs/integration.md)。

## 调度中心模型

任务组、执行器、服务实例注册、心跳在线状态、持久化边界和远程触发协议见 [docs/scheduler-center.md](docs/scheduler-center.md)。

Netty 远程执行器接入见 [docs/netty-executor.md](docs/netty-executor.md)。

HA 节点角色、shard lease、fencing token 和 JDBC 存储见 [docs/ha-cluster.md](docs/ha-cluster.md)。

JDBC store 和 schema 方言脚本见 [docs/jdbc-store.md](docs/jdbc-store.md)。

插件体系、Admin HTTP 和 Prometheus Metrics 见 [docs/plugins.md](docs/plugins.md)。这些插件默认不随 server 加载，需要通过配置显式启用。

模块边界和 executor/server 拆分方向见 [docs/module-boundaries.md](docs/module-boundaries.md)。

镜像构建和容器节点角色配置见 [docs/deployment.md](docs/deployment.md)。

示例程序见 [docs/examples.md](docs/examples.md)。

## 任务示例

```java
JobDefinition job = JobDefinition.builder()
        .id("demo-print-every-5s")
        .name("Demo print every five seconds")
        .handlerName("demoPrinter")
        .schedule(new CronSchedule("*/5 * * * * *"))
        .zoneId(ZoneId.of("Asia/Shanghai"))
        .misfirePolicy(MisfirePolicy.FIRE_ONCE)
        .misfireGrace(Duration.ofSeconds(2))
        .concurrencyPolicy(ConcurrencyPolicy.FORBID)
        .maxCatchUpCount(3)
        .timeout(Duration.ofSeconds(10))
        .enabled(true)
        .build();
```

## 时区语义

每个 cron 任务都有自己的显式 `ZoneId`。

```java
JobDefinition job = JobDefinition.builder()
        .id("new-york-daily-report")
        .name("New York Daily Report")
        .handlerName("reportHandler")
        .schedule(new CronSchedule("0 0 9 * * *"))
        .zoneId(ZoneId.of("America/New_York"))
        .build();
```

这表示 `0 0 9 * * *` 会在纽约本地时间 09:00 执行，不依赖调度服务部署机器的默认时区。

Firefly 的时间规则：

- 运行态调度游标统一使用 UTC `Instant` 存储。
- cron 表达式按 `JobDefinition.zoneId` 对应的本地时间计算。
- 使用 IANA 时区，例如 `Asia/Shanghai`、`America/New_York`、`Europe/Berlin`。
- 对需要遵循夏令时规则的业务调度，避免使用 `+08:00` 这类固定 offset。
- 不使用 `ZoneId.systemDefault()` 表达任务语义。

DST 行为：

- 春季跳时导致不存在的本地时间会被跳过。
- 秋季回拨导致重复出现的本地时间可以触发两次，分别对应两个真实 UTC 时刻。

更多说明见 [docs/timezone.md](docs/timezone.md)。

## 设计原则

- **显式优先**：任务时间、misfire 策略、并发策略都应显式表达。
- **核心克制**：调度核心只处理调度语义，不绑定 Web、存储、远程调用或 IOC 容器。
- **模块演进**：新能力优先放进独立模块，通过接口连接核心。
- **可测试性**：时间计算、仓储一致性、执行策略都应能用单元测试稳定验证。

## 持续优化方向

1. 轻量 HTTP 管理 API。
2. JSON/YAML 任务定义加载。
3. 执行历史和 token-aware runtime state。
4. 执行历史和状态流转。
5. 远程执行器鉴权、TLS、路由策略。
6. 调度分片与本地 TimingIndex 加载。
7. tracing 和更完整的插件发现机制。

## 名字

Firefly 的意思是“萤火虫”。

它轻、小、安静，却能在需要的时候准时发光。未来当调度节点分布在不同机器、不同地域、不同业务服务中时，每个节点都像一点微光，共同组成一个稳定、有秩序的任务网络。

## Admin API 与 UI 约定

Admin HTTP 是可选管理 API，Admin UI 是独立 Node 服务。页面资源不放进 scheduler core，也不随 Admin HTTP jar 内嵌。

- Java 入口：apis/admin-http/src/main/java/com/firefly/api/admin/http/AdminHttpPlugin.java
- Node UI：ui/admin
- 页面路由：/、/jobs、/executors、/nodes
- JSON 接口：/api/health、/api/overview、/api/jobs、/api/executors、/api/nodes

默认启动方式是先启动 Firefly server，再进入 `ui/admin` 执行 `npm start`。Node UI 默认监听 `127.0.0.1:9720`，并将 `/api/*` 代理到 `FIREFLY_ADMIN_API`，默认值为 `http://127.0.0.1:9710`。

不要把完整 HTML 页面内嵌到 Java text block 中；新增管理接口进入 `apis/admin-http`，前端页面与 Node 服务进入 `ui/admin`。

## 目标模块边界

Firefly 后续按运行时、API、UI、传输和客户端拆分模块：

`	ext
libs/scheduler-core        调度核心，纯 Java
server                     运行时装配、启动、生命周期
apis/admin-model          Admin DTO 和 ViewModel
apis/admin-http           Admin HTTP API
ui/admin                  Node 前端工程
plugins/plugin-api        插件 SPI
plugins/metrics-prometheus Prometheus 指标插件
transports/netty          Netty 协议与传输
clients/executor-netty    业务侧执行器 SDK
`

原则上，Admin UI 作为独立 Node 前端演进；Admin API 属于 server/API 层。`apis/admin-http` 和 `ui/admin` 是长期分离的 API/UI 模块。
