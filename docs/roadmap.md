# Firefly roadmap

## 第一阶段：单机轻量核心

目标：先把时间语义和执行语义做对。

- 任务级时区
- Cron / fixed-rate 调度
- misfire 策略
- 并发策略
- 执行器注册
- 内存仓库
- Gradle 多模块
- Guice 装配，仅限 server 层
- 核心单元测试

## 第二阶段：服务化

目标：提供可操作、可观测的调度服务。

- Admin Web 插件增强操作能力
- Prometheus Metrics 插件扩展调度指标
- JSON 配置导入导出
- 任务状态查询
- 执行日志查询
- graceful shutdown
- 指标输出

## 第三阶段：持久化

目标：任务定义、调度游标、执行记录不丢。

- Repository SPI
- token-aware runtime state
- 任务版本号
- 乐观锁更新 next_fire_time
- 执行实例表
- 日志归档

## 第四阶段：分布式调度

目标：让调度能力可以随节点横向扩展，并在节点故障时自然接管。

- scheduler 节点注册和心跳
- shard lease
- fencing token
- 按 tenantId/jobId 一致性哈希
- 分片 rebalance
- 节点故障接管
- 调度延迟指标

## 第五阶段：远程执行器

目标：支持业务服务独立部署执行器。

- executor agent SDK
- HTTP/gRPC 协议
- 心跳与健康检查
- mTLS 或 token 签名
- 执行回调
- 幂等 executionId

## 设计方向

- 任务定义自带 `zoneId`，不是依赖服务器默认时区。
- 调度核心可分片，避免把所有 due job 压到一个中心锁。
- Repository 是接口，数据库只是实现之一。
- misfire 和 concurrency 是明确策略，不藏在执行细节里。
- 核心模块不依赖 Spring，方便嵌入任意 Java 服务。
- 页面、指标、审计和告警通过插件接入，不硬编码进调度核心。

