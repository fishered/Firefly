# Netty Executor

Firefly 的远程执行器推荐使用业务服务主动连接调度中心的方式。

```text
business service
└── NettyExecutorClient  --->  NettyExecutorGateway
                              scheduler center
```

业务服务不需要开放监听端口。调度中心开放一个 executor gateway 端口，业务服务启动后主动建立 Netty 长连接。

## 协议

Netty 只负责长连接和高效 IO，业务协议是 Firefly 自己定义的 JSON 消息。

消息使用 newline-delimited JSON：

```json
{"messageId":"...","type":"REGISTER_EXECUTOR","payload":{"executorName":"billing-executor","instanceId":"billing-1"}}
```

当前消息类型：

```text
REGISTER_EXECUTOR
REGISTERED
REGISTER_REJECTED
HEARTBEAT
TRIGGER_JOB
CANCEL_JOB
ACK_JOB
REPORT_RESULT
UNREGISTER_EXECUTOR
```

注册协议当前版本为 `2`，最低兼容版本为 `1`。客户端上报 `protocolVersion` 和 `capabilities`，Gateway 返回双方能力交集与 `gatewayNodeId`。显式声明能力时必须包含 `TARGET_ACK` 和 `RESULT_REPORT`；`CANCELLATION` 是可选能力，滚动升级期间不会向未协商该能力的旧连接发送取消帧。未携带版本的旧客户端按 v1 降级处理。

`TRIGGER_JOB` 会携带：

```text
executionId
jobId
handlerName
scheduledFireTime
dispatchTime
param.*
```

## 传统项目接入

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

## Spring Boot 接入

引入 `com.firefly:firefly-spring-boot-starter` 后，只需要配置执行器名称：

```yaml
firefly:
  executor:
    name: billing-executor
    auto-start: true
    scheduler-host: 127.0.0.1
    scheduler-port: 9700
    service-name: billing-service
    heartbeat-interval: 10s
    gateway-addresses:
      - firefly-1:9700
      - firefly-2:9700
    reconnect-initial-delay: 1s
    reconnect-max-delay: 30s
    idempotency-directory: /data/firefly-executor-results
    idempotency-retention: 24h
```

业务服务声明 handler：

```java
@Bean
FireflyJobHandlerRegistration billingHandler() {
    return FireflyJobHandlerRegistration.of("billingHandler", context -> {
        // run business code
    });
}
```

## 调度中心侧

```java
NettyExecutorGateway gateway = new NettyExecutorGateway(9700);
gateway.start();
```

调度中心生成 `ExecutionCommand` 后，通过 executorName 路由：

```java
gateway.dispatch("billing-executor", command);
```

`ExecutorResultStore` 是可插拔的执行结果幂等 SPI。默认使用内存实现；配置 `idempotency-directory` 后，Starter 使用文件实现，按 `executionId` 保存已完成结果，支持原子替换、保留期、过期与损坏文件清理。自定义 `ExecutorResultStore` Bean 会覆盖默认选择。

文件存储适合挂载容器持久卷，可覆盖多 Gateway 重复下发和 Executor 重启后的已完成结果重放。它不保证业务处理中途崩溃的绝对 exactly-once，业务 Handler 仍应使用 `rootExecutionId` 或业务唯一键做幂等。

广播和分片默认使用 `FAILED_TARGETS_ONLY`，根据上一 attempt 的目标记录跳过成功目标；`QUORUM` 会把已成功目标结转为 synthetic carry target。任务也可显式配置 `ALL_TARGETS`，让下一 attempt 重跑全部原目标。业务 Handler 仍应使用 `rootExecutionId`、分片键或业务唯一键保证跨 attempt 幂等。

## 业务幂等模板

非 Spring 项目可直接注册带业务幂等保护的 Handler：

```java
client.registerIdempotentHandler(
        "settleOrder",
        businessIdempotencyStore,
        IdempotencyKeyStrategy.parameter("orderId"),
        context -> settlementService.settle(context.parameters().get("orderId"))
);
```

`BusinessIdempotencyStore.tryAcquire` 必须在业务数据库中以唯一键或 CAS 原子实现，并返回 `ACQUIRED`、`IN_PROGRESS` 或 `COMPLETED`。成功后调用 `markCompleted`，异常时调用 `release` 允许重试。`InMemoryBusinessIdempotencyStore` 仅用于测试和单机演示。

Spring Boot 可直接声明 `NamedJobHandler` Bean；Starter 会按 `handlerName()` 自动注册。需要幂等时，也可声明 `FireflyJobHandlerRegistration.idempotent(...)` Bean，并注入业务侧 `BusinessIdempotencyStore`。Spring 与非 Spring 使用同一套语义，不把业务 exactly-once 错误地交给调度中心数据库。

客户端模块提供 `JdbcBusinessIdempotencyStore`。它使用业务数据库时间、行锁、过期 claim 接管和 claim token fencing；旧 owner 在接管后不能提交或释放新 claim。初始化 SQL 位于：

```text
clients/executor-netty/src/main/resources/com/firefly/executor/idempotency/jdbc/h2.sql
clients/executor-netty/src/main/resources/com/firefly/executor/idempotency/jdbc/mysql.sql
clients/executor-netty/src/main/resources/com/firefly/executor/idempotency/jdbc/postgresql.sql
```

Spring Boot 在存在 `DataSource` 时可直接启用：

```yaml
firefly:
  executor:
    netty:
      business-idempotency:
        enabled: true
        abandoned-claim-timeout: 30m
        table-name: firefly_executor_idempotency
```

业务 Handler 可把自动创建的 `BusinessIdempotencyStore` 注入 `FireflyJobHandlerRegistration.idempotent(...)`。`abandoned-claim-timeout` 必须大于任务正常最长执行时间；业务副作用仍应使用同库唯一键或业务事务保证，避免“副作用已提交但幂等完成标记尚未提交”的崩溃窗口。

`JdbcBusinessIdempotencyStore.deleteTerminalBefore(cutoff, limit)` 提供有界清理。保留期必须覆盖业务允许的最大重放窗口；删除已完成 key 后，同一业务 key 将可再次执行。

TLS/mTLS 可通过 Gateway 和 Executor 客户端配置启用。服务端配置 `firefly.executor.gateway.netty.tls.certificate-chain`、`private-key`、`trust-certificates`、`require-client-auth` 和 `reload-interval`；客户端配置 `firefly.executor.tls-*`，包括信任证书、客户端证书和主机名校验。Gateway 会为新连接热加载变化后的 PEM 文件，已有连接保留到自然重连。

注册拒绝响应包含稳定 `reasonCode`；Prometheus 暴露活跃连接、注册拒绝和断线指标。`CANCEL_JOB` 是协作式取消，Executor 会中断本地 Future 并回报 `CANCELLED`，业务 Handler 仍需正确响应线程中断。
