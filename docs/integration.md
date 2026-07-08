# Firefly 集成方案

Firefly 的集成层分成三类入口：传统 Java 项目、Spring Boot 项目、独立 server CLI。核心原则是：调度核心保持纯 Java，框架适配放在独立模块里。

## 模块

```text
integrations
├── embedded               # 传统 Java / 非 Spring 项目的嵌入式门面
├── spring-boot-starter    # Spring Boot 自动装配
└── server-cli             # 独立 server 命令入口占位
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
