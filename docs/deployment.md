# Firefly Docker 部署

Firefly 发布为两个独立镜像：

| 镜像 | 默认容器端口 | 职责 |
| --- | --- | --- |
| `ghcr.io/fishered/firefly` | `9700`、`9710`、`9711` | Gateway、Admin API、Scheduler、Metrics |
| `ghcr.io/fishered/firefly-admin` | `9720` | Web 页面、登录会话和 Admin API 反向代理 |

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
docker build -t ghcr.io/fishered/firefly:1.0.1 -f Dockerfile .
docker build -t ghcr.io/fishered/firefly-admin:1.0.1 -f ui/admin/Dockerfile ui/admin
```

服务镜像使用 Amazon Corretto OpenJDK 21 Alpine 和 Gradle `installDist` 产物，以非 root 用户运行。前端镜像使用 Node 22 Alpine，
不需要安装第三方 npm 依赖。

## 使用已发布镜像

Compose 默认使用 GHCR 公共镜像，并从 `.env` 读取统一仓库和版本：

```dotenv
FIREFLY_IMAGE_REGISTRY=ghcr.io/fishered
FIREFLY_VERSION=1.0.1
```

需要私有镜像仓库时，只需把 `FIREFLY_IMAGE_REGISTRY` 改成目标 registry 和 namespace。

只拉取、不在部署机重新构建：

```powershell
docker compose pull
docker compose up -d --no-build
```

也可以从本地构建结果重新打标签并分别发布：

```powershell
docker tag ghcr.io/fishered/firefly:1.0.1 registry.example.com/firefly/firefly:1.0.1
docker tag ghcr.io/fishered/firefly-admin:1.0.1 registry.example.com/firefly/firefly-admin:1.0.1
docker push registry.example.com/firefly/firefly:1.0.1
docker push registry.example.com/firefly/firefly-admin:1.0.1
```

## 发布到 GitHub Container Registry

`.github/workflows/publish-container-images.yml` 只支持手动触发，不响应普通分支 push。工作流先检出 `vX.Y.Z` Tag，并校验 Tag 和 Gradle 版本一致，然后构建以下 `linux/amd64` 镜像：

```text
ghcr.io/fishered/firefly:X.Y.Z
ghcr.io/fishered/firefly:X.Y
ghcr.io/fishered/firefly:latest          # 可选

ghcr.io/fishered/firefly-admin:X.Y.Z
ghcr.io/fishered/firefly-admin:X.Y
ghcr.io/fishered/firefly-admin:latest    # 可选
```

首次使用前，在仓库 `Settings -> Actions -> General -> Workflow permissions` 确认 Actions 允许读写 Package。发布 `1.0.1`：

```powershell
gh workflow run publish-container-images.yml `
  --ref master `
  -f version=1.0.1 `
  -f publish_latest=true

gh run list --workflow publish-container-images.yml --limit 5
gh run watch <RUN_ID> --exit-status
```

也可以在 GitHub 的 `Actions -> publish-container-images -> Run workflow` 中输入版本。工作流必须从已经存在且不可变的版本 Tag 构建，不允许用普通分支提交冒充正式镜像。

第一次发布完成后，进入 GitHub 个人主页的 `Packages`，分别打开 `firefly` 和 `firefly-admin`：

```text
Package settings -> Danger Zone -> Change visibility -> Public
```

两个 Package 都设为 Public 后，未登录用户才能直接拉取。验证公开访问和平台信息：

```powershell
docker logout ghcr.io
docker pull ghcr.io/fishered/firefly:1.0.1
docker pull ghcr.io/fishered/firefly-admin:1.0.1
docker buildx imagetools inspect ghcr.io/fishered/firefly:1.0.1
docker buildx imagetools inspect ghcr.io/fishered/firefly-admin:1.0.1
```

GHCR 标签在技术上可以覆盖，但 Firefly 的 `X.Y.Z` 标签按不可变版本管理。失败重试必须继续使用同一个未移动的 Git Tag；版本代码发生变化时创建新版本，不能覆盖已公开镜像。

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
  ghcr.io/fishered/firefly:1.0.1

docker run -d --name firefly-admin-ui --network firefly `
  -p 9720:9720 `
  -e FIREFLY_ADMIN_API=http://firefly-server:9710 `
  ghcr.io/fishered/firefly-admin:1.0.1
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
