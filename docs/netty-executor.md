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
ACK_JOB
REPORT_RESULT
UNREGISTER_EXECUTOR
```

注册协议当前版本为 `1`。客户端上报 `protocolVersion` 和 `capabilities`，Gateway 返回双方能力交集与 `gatewayNodeId`。显式声明能力时必须包含 `TARGET_ACK` 和 `RESULT_REPORT`；Gateway 与客户端都会拒绝不支持的版本或不完整的协商结果。未携带版本的旧客户端按 v1 兼容处理。

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

引入 `integrations:netty-spring-boot-starter` 后，显式开启：

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
NettyJobHandlerRegistration billingHandler() {
    return NettyJobHandlerRegistration.of("billingHandler", context -> {
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

广播和分片的重试会根据上一 attempt 的目标记录跳过已经成功的目标，只重新派发失败、超时或缺失目标；`QUORUM` 会把已成功目标结转为 synthetic carry target，避免为了凑法定多数重复执行已经成功的实例。单播按单目标重试。业务 Handler 仍应使用 `rootExecutionId` 或业务唯一键保证跨 attempt 幂等。

TLS/mTLS 可通过 Gateway 和 Executor 客户端配置启用。服务端配置 `firefly.executor.gateway.netty.tls.certificate-chain`、`private-key`、`trust-certificates` 和 `require-client-auth`；客户端配置 `firefly.executor.netty.tls-*`，包括信任证书、客户端证书和主机名校验。当前证书加载在启动时完成，证书轮换需要重启进程。

仍需补的能力：

- 证书热轮换
- 更细的注册拒绝与连接状态观测
