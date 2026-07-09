# Firefly Examples

Firefly 示例放在 `examples/*`，用于验证不同集成方式。

## Embedded Basic

模块：`examples:embedded-basic`

用途：模拟传统 Java 服务在进程内集成 Firefly。

运行：

```powershell
.\gradlew.bat :examples:embedded-basic:run
```

这个示例会：

- 创建一个 in-process `FireflyScheduler`。
- 注册一个每 2 秒触发一次的任务。
- 打印执行上下文。
- 成功执行 3 次或等待 8 秒后自动退出。

它不依赖 Spring，也不启动 server、Admin Web 或 Prometheus Metrics。

## Netty Executor Basic

模块：`examples:netty-executor-basic`

用途：模拟真实业务服务作为远程 executor 连接 Firefly server。

第一步，启动 server，并启用 Admin API 和 Netty executor gateway：

```powershell
.\gradlew.bat :server:run --args="--firefly.node.mode=standalone --firefly.plugins=admin-web --firefly.executor.gateway.netty.enabled=true"
```

第二步，启动业务 executor example：

```powershell
.\gradlew.bat :examples:netty-executor-basic:run
```

server 控制台应看到 executor 注册日志。

第三步，通过 API 创建远程任务：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:9710/api/jobs `
  -ContentType "application/json" `
  -Body '{"id":"remote-example-job","executorName":"example-executor","handlerName":"exampleHandler","cron":"*/5 * * * * *","zoneId":"Asia/Shanghai","param.source":"api"}'
```

之后 server 会按 cron 调度任务，并通过 Netty gateway 触发 example 中注册的 handler。
