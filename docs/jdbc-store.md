# JDBC Store

`stores:jdbc` 提供持久化任务仓库和 HA 协调存储。

它不会默认启用。server 或宿主应用需要通过配置显式选择 JDBC store。

## Schema 初始化

JDBC schema 不在 Java 代码里硬编码建表 SQL。`JdbcSchema` 负责：

- 根据 `DatabaseMetaData` 自动识别数据库方言。
- 或使用 `JdbcSchemaOptions` 指定方言。
- 加载各数据库方言对应的 SQL 资源。PostgreSQL 的权威源文件是 `scripts/postgresql/init.sql`，
  构建时会自动打包到 JDBC 模块的 schema 资源目录。
- 按脚本顺序执行建表语句。

当前脚本：

```text
stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/h2.sql
scripts/postgresql/init.sql
stores/jdbc/src/main/resources/com/firefly/store/jdbc/schema/mysql.sql
```

默认自动识别：

```java
JdbcSchema.initialize(dataSource);
```

显式指定：

```java
JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("postgresql"));
```

## 为什么不用一套硬编码 SQL

不同数据库在这些地方会有差异：

- `timestamp` 是否带时区。
- `boolean` 类型的实际表示。
- `text` / `varchar` 长度限制。
- `create index if not exists` 支持情况。
- identifier 大小写和保留字规则。

因此 Firefly 使用 dialect script，而不是在 `JdbcSchema` 里写死一份 SQL。

PostgreSQL 全量脚本只用于全新数据库。已有数据库由 `JdbcSchema` 根据 `firefly_schema_version`
依次执行 `schema/migrations/postgresql/v*.sql`；新增 PostgreSQL 迁移时必须同步更新全量脚本。
