# Firefly Remote Adapter

`firefly-remote-adapter` 面向传统 Java、Servlet、Guice、命令行 Worker 等非 Spring 服务。
它复用 Firefly 的 Netty Executor 通道，但向业务代码提供固定执行器、Handler 注册、配置加载和生命周期管理。

Remote Adapter 是业务服务侧适配器，不是调度器，也不是 Executor 管理客户端：

- Executor 必须先在 Firefly Admin 中创建，协议选择 `TCP`。
- Adapter 只注册运行实例及其 Handler 能力，不创建或更新 Executor 定义。
- Job、Cron、重试、路由和启停状态继续由 Admin UI/API 管理。
- 未创建的 `executorName` 会被 Gateway 拒绝，Adapter 启动失败且不会自动补建定义。
- Spring Boot 服务继续使用原有 Starter；Remote Adapter 不替代或修改 Starter。

Python、Go 和通用 HTTP Agent 不在 v1.0.5 范围内。后续 Agent 应保持语言无关，具体语言 SDK 只封装协议和本地 Handler 调用。

## 引入依赖

```xml
<dependency>
    <groupId>io.github.fishered</groupId>
    <artifactId>firefly-remote-adapter</artifactId>
    <version>1.0.5</version>
</dependency>
```

运行环境要求 Java 21。

## 推荐接入方式

业务程序在明确传入的对象上标记 Handler，然后让 Adapter 跟随进程生命周期运行：

```java
import com.firefly.domain.ExecutionContext;
import com.firefly.integration.remote.FireflyHandler;
import com.firefly.integration.remote.RemoteExecutorAdapter;
import com.firefly.integration.remote.RemoteHandlerProvider;

final class BillingHandlers {
    @FireflyHandler
    void billing(ExecutionContext context) {
        // business code
    }

    @FireflyHandler
    void reconcile() {
        // business code
    }
}

public final class BillingApplication {
    public static void main(String[] args) throws InterruptedException {
        RemoteExecutorAdapter.run(
                RemoteHandlerProvider.annotated(new BillingHandlers())
        );
    }
}
```

`@FireflyHandler` 是 Remote Adapter 自己的 JDK 运行时注解，不依赖 Spring。Adapter 使用业务类全限定名和方法名生成稳定入口，例如 `com.example.BillingHandlers#billing`。方法必须返回 `void`，参数必须为空或只有一个 `ExecutionContext`。

同一业务类的注解重载方法会生成相同入口并在连接前失败，避免调度入口含糊。超长入口与 Starter 一样使用“可读前缀 + SHA-256 摘要”稳定缩短。Adapter 只扫描明确传入的对象，不扫描 classpath，也不会从注解生成 Job 或调度配置。

`RemoteExecutorAdapter.run(...)` 会加载配置、连接 Gateway、等待注册成功，并安装 JVM 关闭钩子。

## 低层程序化注册

确实需要兼容外部动态 Handler 名称时，可以使用低层 Registry API：

```java
RemoteExecutorAdapter.run(handlers -> handlers
        .bind("legacy-billing", billingService::execute));
```

字符串名称由业务代码负责稳定性，只建议用于兼容或动态适配；常规固定业务方法使用 `@FireflyHandler` 自动入口。

## 配置

Adapter 按以下优先级读取配置：JVM system property、环境变量、classpath 根目录的 `firefly-remote-adapter.properties`、默认值。

最小环境变量配置：

```text
FIREFLY_EXECUTOR_NAME=billing-executor
FIREFLY_EXECUTOR_GATEWAY_ADDRESSES=firefly-1:9700,firefly-2:9700
FIREFLY_EXECUTOR_INTEGRATION_KEY=replace-with-integration-key
```

等价 properties：

```properties
firefly.executor.name=billing-executor
firefly.executor.gateway-addresses=firefly-1:9700,firefly-2:9700
firefly.executor.integration-key=replace-with-integration-key
firefly.executor.instance-id=billing-1
firefly.executor.service-name=billing-service
firefly.executor.startup-timeout=30s
firefly.executor.heartbeat-interval=10s
firefly.executor.reconnect-initial-delay=1s
firefly.executor.reconnect-max-delay=30s
firefly.executor.idempotency-directory=/data/firefly-executor-results
firefly.executor.idempotency-retention=24h
```

TLS 键与 Spring Starter 保持一致：

```properties
firefly.executor.tls-enabled=true
firefly.executor.tls-certificate-chain=/etc/firefly/client.crt
firefly.executor.tls-private-key=/etc/firefly/client.key
firefly.executor.tls-private-key-password=
firefly.executor.tls-trust-certificates=/etc/firefly/ca.crt
firefly.executor.tls-verify-hostname=true
```

## 自主管理生命周期

已有容器或服务框架可以显式管理 Adapter：

```java
RemoteAdapterOptions options = RemoteAdapterOptions.builder()
        .executorName("billing-executor")
        .gatewayAddresses(List.of("firefly-1:9700", "firefly-2:9700"))
        .integrationKey(System.getenv("FIREFLY_INTEGRATION_KEY"))
        .build();

try (RemoteExecutorAdapter adapter = RemoteExecutorAdapter.create(
        options,
        RemoteHandlerProvider.annotated(new BillingHandlers())
)) {
    adapter.start();
    applicationRuntime.runUntilShutdown();
}
```

`start()` 只有在至少一个 Gateway 返回 `REGISTERED` 后才成功。所有 Gateway 明确拒绝注册时立即失败；连接超时则按 `startup-timeout` 失败。`isReady()` 可用于宿主服务自己的 readiness 检查。

## 控制面操作顺序

1. 在 Admin 创建固定 Executor，例如 `billing-executor`，协议选择 `TCP`。
2. 部署业务服务，配置同一个 `firefly.executor.name` 并启动 Adapter。
3. 在 Admin 确认实例在线且 Handler 能力已经上报。
4. 创建 Job，并选择该 Executor 和对应 Handler。
5. 在 Admin 管理 Cron、启停、路由、重试和执行记录。

这一顺序保证程序只声明“我能执行什么”，控制面决定“何时、如何调度”。
