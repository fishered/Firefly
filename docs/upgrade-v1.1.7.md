# 从 Firefly 1.1.6 升级到 1.1.7

1.1.7 是兼容升级：Executor 协议仍为 v1/v2，插件 API level 仍为 1，任务定义和已有 Admin API 字段不变。数据库兼容版本仍为 12；v19 feature migration 只增加事件 inbox 字段、持久化 nonce 表和查询索引，旧节点会忽略这些附加对象。

## 升级前

1. 暂停变更类 Admin 操作并确认活动 Outbox 已收敛。
2. 备份数据库。PostgreSQL 可使用 `scripts/production/verify-postgresql-backup-restore.sh` 在独立空库完成一次恢复校验。
3. 为所有 API 节点生成同一个独立事件 HMAC 密钥并配置 `FIREFLY_TRIGGER_HMAC_SECRET`。不要复用 JWT、数据库或 Gateway 内部转发密钥。
4. 先让事件生产方发送 1.1.7 所需的签名头；1.1.6 会忽略这些附加头，因此可以提前部署生产方。

## 滚动升级

JDBC 使用 `firefly.jdbc.schema.mode=initialize-if-empty` 时，首个 1.1.7 节点会幂等执行 v19 feature migration。使用外部迁移系统时，先执行对应方言的 `stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/migrations/<dialect>/v19.sql`，再以 `validate` 模式启动节点。

按普通排空流程逐个替换节点。1.1.6 与 1.1.7 可以在同一数据库和 Executor 集群中短暂共存；升级窗口内只把外部事件流量发送到已经配置 HMAC 的 1.1.7 API 节点。

## 行为变化

- `/api/triggers/{jobId}` 现在 fail-closed。未配置密钥返回 `503`；缺失、非法或过期签名返回 `401`；重复 nonce 返回 `409`。
- JDBC Server 默认使用 `JdbcTriggerInbox`，幂等 key 和 nonce 在重启后继续有效。
- execution、Backfill、审计、任务历史和 Dead Outbox 列表接受 `limit`、`cursor` 和各自过滤条件；响应保留原列表字段并增加 `nextCursor`、`hasMore`。
- 默认页大小不再代表总记录上限；调用方应原样回传 opaque `nextCursor`，不要解析或自行修改。

## 验证与回滚

```powershell
.\gradlew.bat test
.\gradlew.bat verifyPluginCompatibilityMatrix verifyReleaseVersion
.\gradlew.bat verifyProductionReadiness
```

验证签名事件只创建一个 root execution，重复 nonce 被拒绝，使用新 nonce 重试相同 idempotency key 返回 duplicate；再验证失败事件查询/重放、管理端翻页和 Prometheus trigger 指标。

v19 是纯增量变更，紧急回滚到 1.1.6 时不需要删除新增列、表或索引。回滚节点不会使用持久化 nonce，因此回滚期间应在上游暂停外部事件，避免降低防重放保证。
