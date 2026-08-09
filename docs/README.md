# Firefly 文档索引

仓库内文档面向开发、集成和运维场景。面向使用者的快速入门与配置参考仍以
[Firefly 在线文档](https://fishered.github.io/firefly-home/)为入口。

## 架构与开发

| 文档 | 内容 |
| --- | --- |
| [design.md](design.md) | 核心架构、模块边界、运行时不变量和通信边界 |
| [development.md](development.md) | 分支命名、提交粒度、质量门禁和设计约束 |
| [implementation-progress.md](implementation-progress.md) | 当前已经落地的能力 |
| [v1.0.2-roadmap.md](v1.0.2-roadmap.md) | 1.0.2 增量范围、非目标和验收标准 |

## 核心能力

| 文档 | 内容 |
| --- | --- |
| [scheduler-center.md](scheduler-center.md) | 调度模型、任务、执行器与状态推进 |
| [netty-executor.md](netty-executor.md) | Executor 协议、接入和业务幂等 |
| [plugins.md](plugins.md) | 插件 SPI、加载方式和内置插件 |
| [timezone.md](timezone.md) | 时区和 DST 语义 |
| [examples.md](examples.md) | Embedded 与 Netty Executor 示例 |

## 存储与运行

| 文档 | 内容 |
| --- | --- |
| [database-schema.md](database-schema.md) | 数据库结构、迁移和 SchemaTool |
| [jdbc-store.md](jdbc-store.md) | JDBC Store 的方言与初始化边界 |
| [ha-cluster.md](ha-cluster.md) | 节点、分片租约、fencing 和故障恢复 |
| [deployment.md](deployment.md) | Docker、节点角色和生产部署 |
| [integration.md](integration.md) | Java、Spring Boot 与远程 Executor 集成 |
| [maven-central-publishing.md](maven-central-publishing.md) | Maven Central 发布流程 |

## 文档维护

- 一个主题只保留一个权威说明；其他文档使用链接引用，不复制整段内容。
- 阶段性完成情况只写入 `implementation-progress.md`，版本内计划只写入对应 roadmap。
- 代码、配置或协议发生变化时，在同一功能分支同步更新对应文档。
- 不提交由现有 UI 重复生成且没有构建或评审用途的 HTML、截图和设计导出物。
