# Cloud Native Feature

本 Feature 将现有节点角色包装为可独立部署和扩缩容的 Kubernetes 工作负载，不用 Kubernetes Service 或选主机制替代数据库 lease/fencing。

## 实施拆分

1. Helm chart 拆分 `firefly-scheduler`、`firefly-gateway`、`firefly-api` 和 `firefly-admin-ui`，共享配置使用 Secret/ConfigMap 管理。
2. Scheduler 按 shard backlog 和维护延迟扩缩容；Gateway 按连接数、Outbox backlog 和 ACK 延迟扩缩容；API 按 HTTP 延迟扩缩容。
3. 增加 readiness/liveness/startup probe、PDB、滚动升级前的 schema/版本兼容检查和 drain 流程。
4. 提供 ServiceMonitor、OTLP Collector 示例、Grafana dashboard、PrometheusRule、NetworkPolicy 和最小权限 ServiceAccount。
5. 发布灰度时先升级 API/UI，再升级 Gateway，最后升级 Scheduler；旧版本必须能安全忽略 additive 字段。

## 代码边界

- `deploy/helm/firefly`: chart、模板、默认资源与安全策略。
- `server/bootstrap` / `server/runtime`: 角色启动、健康检查、drain 和版本兼容检查。
- `plugins/metrics-prometheus` / `plugins/tracing-opentelemetry`: 暴露 HPA 与 OTLP 所需指标和 trace。
- `docs/deployment.md`: 集群升级、扩缩容、故障回滚和数据库迁移说明。

## 验收重点

- Scheduler、Gateway、API 可以独立扩缩容，且不会产生重复调度或重复派发。
- 节点被驱逐时先 drain，未完成 execution/Outbox 能由其他节点 fenced 接管。
- readiness 在 schema、插件、数据库时钟和节点 online 状态未就绪时保持失败。
- Helm 默认值不包含明文密钥，镜像以非 root 用户运行并有资源上限。
