# Firefly Docker 部署

Firefly 发布为两个独立镜像：

| 镜像 | 默认容器端口 | 职责 |
| --- | --- | --- |
| `firefly/firefly-server` | `9700`、`9710`、`9711` | Gateway、Admin API、Scheduler、Metrics |
| `firefly/firefly-admin-ui` | `9720` | Web 页面、登录会话和 Admin API 反向代理 |

PostgreSQL 是运行依赖，不属于 Firefly 自身镜像。镜像不固化数据库地址、节点名称或密码，相同镜像可以通过
环境变量运行成单节点、全角色集群节点或专用角色节点。

## 一键启动

仓库根目录已经提供 `docker-compose.yml` 和 `.env.example`：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

`FIREFLY_CONFIG_PROFILE` 是唯一的存储类型选择器：

```dotenv
# PostgreSQL
FIREFLY_CONFIG_PROFILE=pg

# 本地 H2 文件存储
FIREFLY_CONFIG_PROFILE=h2

# 仅用于临时测试的内存存储
FIREFLY_CONFIG_PROFILE=memory
```

使用 `pg` 时保留 `.env` 中的 `FIREFLY_STORE_TYPE` 和 `FIREFLY_JDBC_*` 六项。使用 `h2` 或 `memory` 时删除或注释这六项，Firefly 会直接加载镜像内对应的 profile 默认配置，不需要数据库地址、账号或密码。

生产或共享环境至少修改 `.env` 中的：

```dotenv
FIREFLY_JDBC_PASSWORD=change-me-database-password
FIREFLY_SECURITY_JWT_SECRET=change-me-to-a-long-random-signing-secret
```

启动完成后访问：

```text
Admin UI:  http://127.0.0.1:9720
Admin API: http://127.0.0.1:9710/api/health
Metrics:   http://127.0.0.1:9711/metrics
Gateway:   127.0.0.1:9700
```

全新数据库初始化时会创建默认账号 `admin/admin`。初始化 SQL 使用幂等插入，已存在的 admin 用户及其修改后的密码不会被覆盖；首次登录后应立即修改默认密码。`pg` profile 默认开启认证，`h2` 和 `memory` profile 默认关闭认证，可通过 `FIREFLY_SECURITY_JWT_ENABLED` 显式覆盖。认证关闭时 Admin UI 会自动进入本地管理会话，不显示登录页。

首次登录后进入“配置”，生成 Integration Key。业务服务通过该密钥连接 Gateway 并同步注解任务：

```yaml
firefly:
  executor:
    name: billing-executor
    gateway-addresses:
      - 127.0.0.1:9700
    integration-key: ${FIREFLY_INTEGRATION_KEY}
    job-registration:
      admin-url: http://127.0.0.1:9710
```

## 分别构建两个镜像

```powershell
docker build -t firefly/firefly-server:1.1.3 -f Dockerfile .
docker build -t firefly/firefly-admin-ui:1.1.3 -f ui/admin/Dockerfile ui/admin
```

服务镜像使用 Amazon Corretto OpenJDK 21 Alpine 和 Gradle `installDist` 产物，以非 root 用户运行。前端镜像使用 Node 22 Alpine，
不需要安装第三方 npm 依赖。

## 使用已发布镜像

Compose 默认使用固定镜像名和 `.env` 中的统一版本：

```dotenv
FIREFLY_VERSION=1.1.3
```

需要私有仓库时，对镜像重新打标签，并在部署侧 Compose 覆盖文件中替换 `image` 地址；运行配置不再承担镜像仓库选择职责。

只拉取、不在部署机重新构建：

```powershell
docker compose pull
docker compose up -d --no-build
```

也可以分别发布：

```powershell
docker tag firefly/firefly-server:1.1.3 registry.example.com/firefly/firefly-server:1.1.3
docker tag firefly/firefly-admin-ui:1.1.3 registry.example.com/firefly/firefly-admin-ui:1.1.3
docker push registry.example.com/firefly/firefly-server:1.1.3
docker push registry.example.com/firefly/firefly-admin-ui:1.1.3
```

## 分别运行容器

已有 PostgreSQL 时可以不使用 Compose：

```powershell
docker network create firefly

docker run -d --name firefly-server --network firefly `
  -p 9700:9700 -p 9710:9710 -p 9711:9711 `
  -e FIREFLY_NODE_MODE=standalone `
  -e FIREFLY_NODE_NAME=firefly-standalone `
  -e FIREFLY_NODE_ROLES=api,gateway,scheduler `
  -e FIREFLY_ADMIN_HTTP_HOST=0.0.0.0 `
  -e FIREFLY_METRICS_PROMETHEUS_HOST=0.0.0.0 `
  -e FIREFLY_STORE_TYPE=jdbc `
  -e FIREFLY_JDBC_URL=jdbc:postgresql://postgres:5432/firefly `
  -e FIREFLY_JDBC_USERNAME=firefly `
  -e FIREFLY_JDBC_PASSWORD=change-me `
  -e FIREFLY_JDBC_DIALECT=postgresql `
  -e FIREFLY_JDBC_SCHEMA_MODE=initialize-if-empty `
  -e FIREFLY_SECURITY_JWT_ENABLED=true `
  -e FIREFLY_SECURITY_JWT_SECRET=change-me-to-a-long-random-signing-secret `
  firefly/firefly-server:1.1.3

docker run -d --name firefly-admin-ui --network firefly `
  -p 9720:9720 `
  -e FIREFLY_ADMIN_API=http://firefly-server:9710 `
  firefly/firefly-admin-ui:1.1.3
```

`FIREFLY_ADMIN_API` 必须是前端容器可以访问的地址。容器内的 `127.0.0.1:9710` 指向前端容器自身，不能
用于访问另一个 Firefly Server 容器。

## 节点角色与高可用

默认单节点配置：

```dotenv
FIREFLY_NODE_MODE=standalone
FIREFLY_NODE_NAME=firefly-standalone
FIREFLY_NODE_ROLES=api,gateway,scheduler
```

集群节点必须共享 PostgreSQL，每个容器使用唯一节点名：

```dotenv
FIREFLY_NODE_MODE=cluster
FIREFLY_NODE_NAME=firefly-node-1
FIREFLY_NODE_ROLES=api,gateway,scheduler
```

同一个 Server 镜像可以继续启动 `firefly-node-2`、`firefly-node-3`。三个节点应配置相同数据库、JWT 密钥、
`FIREFLY_SCHEDULER_SHARD_COUNT` 和 Gateway 内部转发密钥，但必须使用不同的 `FIREFLY_NODE_NAME`。

生产环境推荐至少三个全角色节点，前置负载均衡暴露 Admin API；Executor 配置多个 Gateway 地址。需要拆分职责时，
可以分别配置 `api`、`gateway`、`scheduler`，但专用 Gateway 和 Scheduler 节点不应使用依赖 `/api/health` 的
Compose 健康检查。

`FIREFLY_SCHEDULER_SHARD_COUNT` 是集群首次初始化后的共享契约，不能通过普通滚动更新修改。Gateway 内部转发、
分片重建、排空下线和生产告警约束见 [ha-cluster.md](ha-cluster.md) 与 [database-schema.md](database-schema.md)。

## 受控在线分片扩容

在线扩容只保持纯 `GATEWAY`/`EXECUTOR` 数据面节点可用，不是全角色无感热切换。执行顺序如下：

1. 备份数据库，并暂停 Admin 写入。
2. 排空所有包含 `SCHEDULER`、`STANDBY` 或 `API` 角色的节点，等待它们在 `firefly_node` 中变为 `OFFLINE`。混合角色节点也必须整体下线。
3. 等待所有 execution 进入终态、所有 Outbox 进入 `DONE` 或 `DEAD`。工具会再次检查并在条件不满足时拒绝执行。
4. 使用目标分片数执行 `expand-online`。目标值必须大于等于当前值，等于当前值时作为幂等空操作返回。

```powershell
.\gradlew.bat :server:launcher:migrateSchema --args="--firefly.config.profile=pg --firefly.schema.action=expand-online --firefly.schema.reshard.confirm=true --firefly.scheduler.shard-count=64"
```

5. 将所有 Firefly 节点的 `FIREFLY_SCHEDULER_SHARD_COUNT` 更新为新值，再启动 API、Scheduler 和 Standby 节点。
6. 验证节点恢复、全部新 shard lease 被领取、调度延迟和 Outbox 指标正常后恢复 Admin 写入。

该操作在数据库迁移锁和单个事务内重算任务 shard、更新集群元数据并删除旧 lease。在线缩容会被拒绝；需要缩容时必须安排全停机窗口并使用 `firefly.schema.action=reshard`。操作失败会回滚数据库事务，已经下线的节点仍需使用数据库中实际的 shard count 启动。

## 数据与外部插件

Compose 使用 `firefly-postgres` volume 保存全部调度数据。删除容器不会删除数据；执行
`docker compose down -v` 会删除数据库卷，应仅在确认不再需要数据时使用。

Firefly Server 镜像预留 `/opt/firefly/plugins`。使用外部插件时，将插件 JAR 挂载到该目录，并在环境变量中
加入插件 ID：

```yaml
volumes:
  - ./plugins:/opt/firefly/plugins:ro
environment:
  FIREFLY_PLUGINS: metrics-prometheus,acme-alerts
  FIREFLY_PLUGINS_DIRECTORY: /opt/firefly/plugins
```

## 常用命令

```powershell
docker compose logs -f firefly-server
docker compose logs -f firefly-admin-ui
docker compose restart firefly-server firefly-admin-ui
docker compose down
```

修改普通环境变量后执行 `docker compose up -d` 会重建相关容器。修改 Java 或前端代码后使用
`docker compose up -d --build` 重建镜像。
