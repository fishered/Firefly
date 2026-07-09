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
HEARTBEAT
TRIGGER_JOB
ACK_JOB
REPORT_RESULT
UNREGISTER_EXECUTOR
```

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

后续要补的能力：

- 执行结果持久化
- gateway 鉴权
- TLS
- 重连退避
- executor 路由策略
- Netty gateway 与调度中心 Guice 装配
