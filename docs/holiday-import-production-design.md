# 业务日历官方节假日导入生产方案

当前代码已先落地 `com.firefly.schedule.holiday` 的 Provider 契约、不可变数据集、导入批次、日期规则和数据集校验器；JDBC v14 已增加来源元数据、日期规则和导入批次表。Provider 数据源和 Admin preview/publish 接口仍需在下一阶段接线，不能把当前状态表述为官方节假日导入已经完成。

## 1. 目标与边界

官方节假日导入不能由前端写死，也不能仅由 timezone 推断法域。`Asia/Shanghai` 只表示日期解释时使用上海时区；法定节假日必须由单独的 `jurisdiction`（例如 `CN`、`US-FEDERAL`、`JP`）确定。

导入结果必须保留来源、版本、校验值和人工覆盖关系。调度器只读取已经发布的日历版本，不在触发线程中访问外部节假日服务。

## 2. 领域模型

```text
CalendarDefinition
  id / version / zoneId / jurisdiction
  weeklyWorkingDays
  publishedAt / publishedBy

HolidayImportBatch
  importId / calendarId / targetVersion
  providerId / providerVersion / jurisdiction
  rangeStart / rangeEnd / sourceUri
  sourceChecksum / status / importedAt / importedBy

CalendarDateRule
  calendarId / calendarVersion / date
  kind: HOLIDAY | WORKDAY
  name / source: OFFICIAL | MANUAL
  locked: official source may be protected by policy
  importId / reason

CalendarDateOverride
  calendarId / date / kind / reason / operator / createdAt
```

`CalendarDefinition.holidays` 和 `extraWorkingDays` 可以继续作为运行时快照字段，但它们必须由发布流程从 `CalendarDateRule` 投影生成，不能作为唯一事实来源。

## 3. Provider SPI

核心模块定义纯接口，禁止核心调度逻辑依赖网络：

```java
public interface HolidayProvider {
    String id();
    Set<String> supportedJurisdictions();
    HolidayDataset fetch(HolidayQuery query) throws HolidayProviderException;
}

public record HolidayQuery(
        String jurisdiction,
        ZoneId zoneId,
        LocalDate from,
        LocalDate to,
        String expectedProviderVersion
) {}

public record HolidayDataset(
        String providerId,
        String providerVersion,
        String jurisdiction,
        LocalDate from,
        LocalDate to,
        List<HolidayOccurrence> occurrences,
        URI sourceUri,
        String checksum,
        boolean official
) {}

public record HolidayOccurrence(
        LocalDate date,
        HolidayKind kind,
        String localName,
        String name,
        boolean observed,
        String sourceReference
) {}
```

生产环境至少提供两类 provider：

1. `ClasspathHolidayProvider`：随版本发布、经过代码评审和测试的官方数据包，适合中国国务院年度节假日等没有稳定公共 API 的数据。
2. `RemoteHolidayProvider`：从企业批准的 HTTPS 源读取签名数据；必须校验 TLS、签名/sha256、响应大小、时间范围和 provider 版本，失败时不得覆盖上一份已发布数据。

不要直接依赖免费公共 API 作为唯一生产来源。公共 API 可能改口径、限流、缺失调休或发生历史修订；应该把远程数据下载成可审计的导入批次，再由管理员发布。

## 4. 导入与发布流程

```text
选择法域 + 时区 + 年份范围
  -> HolidayProvider 拉取/读取数据
  -> 校验签名、checksum、日期范围、重复项、工作日冲突
  -> 生成 HolidayImportBatch(status=STAGED)
  -> 与现有人工规则做差异预览
  -> 管理员选择“保留人工覆盖/允许官方更新”
  -> 生成新的 CalendarDefinition version
  -> 原子发布版本
  -> 后续触发只读取新版本
```

导入绝不能直接修改正在使用的版本。发布采用新版本，已有 execution 继续使用创建时的 calendar snapshot；只有尚未展开的触发使用新版本。

冲突策略必须显式选择：

- `KEEP_MANUAL`：人工规则优先，官方日期只作为建议。
- `OFFICIAL_WINS`：官方规则覆盖未锁定的人工规则。
- `REJECT_CONFLICT`：存在冲突时整个批次不允许发布。

默认使用 `KEEP_MANUAL`，并把冲突列入预览和审计日志。

## 5. JDBC 迁移建议（v14）

```sql
alter table firefly_calendar add column jurisdiction varchar(64) not null default 'CUSTOM';
alter table firefly_calendar add column published_at timestamp null;
alter table firefly_calendar add column published_by varchar(128) null;

create table firefly_calendar_date_rule (
  calendar_id varchar(128) not null,
  calendar_version bigint not null,
  rule_date date not null,
  rule_kind varchar(16) not null,
  rule_name varchar(256) not null,
  source varchar(16) not null,
  locked boolean not null default false,
  import_id varchar(128) null,
  reason varchar(1024) not null,
  primary key (calendar_id, calendar_version, rule_date),
  foreign key (calendar_id, calendar_version)
    references firefly_calendar(calendar_id, calendar_version)
);

create table firefly_calendar_import (
  import_id varchar(128) primary key,
  calendar_id varchar(128) not null,
  target_version bigint not null,
  provider_id varchar(128) not null,
  provider_version varchar(128) not null,
  jurisdiction varchar(64) not null,
  zone_id varchar(128) not null,
  range_start date not null,
  range_end date not null,
  source_uri varchar(1024) not null,
  source_checksum varchar(128) not null,
  status varchar(32) not null,
  conflict_count integer not null default 0,
  imported_at timestamp not null,
  imported_by varchar(128) not null,
  published_at timestamp null
);
```

实际迁移要分别适配 PostgreSQL、MySQL、H2 的布尔类型和 `alter table ... if not exists` 能力，并为 `(calendar_id, rule_date, source)`、`status` 增加查询索引。

## 6. Admin API

```text
GET  /api/holiday-providers?jurisdiction=CN
POST /api/calendars/{id}/holiday-imports/preview
POST /api/calendars/{id}/holiday-imports
GET  /api/calendars/{id}/holiday-imports/{importId}
POST /api/calendars/{id}/holiday-imports/{importId}/publish
POST /api/calendars/{id}/overrides
GET  /api/calendars/{id}/dates?from=...&to=...
```

所有接口都必须限制年份跨度（建议最多 10 年）、payload 大小、单次日期数量和并发导入数；发布接口需要 `ADMIN` 或专门的 `CALENDAR_PUBLISHER` 权限。

## 7. 前端交互

日历编辑器顶部增加两个独立控件：

- `法域/节假日集`：例如“中国大陆法定节假日（国务院）”。
- `时区`：例如 `Asia/Shanghai`。

增加“导入官方节日”按钮，打开导入抽屉：年份范围、数据来源、导入模式、冲突数量和差异列表。导入后先进入预览态，必须点击“发布为新版本”才改变日历。

月历中使用来源标记：

- 蓝色：每周模板工作日
- 红色实心：官方节假日
- 琥珀色：官方调休工作日
- 紫色边框：人工覆盖

鼠标多选和批量应用仍然可用，但人工修改官方日期时必须显示“将创建人工覆盖”，不能悄悄改掉官方记录。

## 8. 调度安全要求

- 触发线程只读取已发布快照，不进行网络 IO。
- provider 超时、签名失败、数据冲突时，保留上一发布版本并产生告警。
- 每次发布记录 provider、版本、checksum、操作人和差异摘要。
- 日历版本和 execution snapshot 一起写入，保证历史执行可重放。
- 法定节日数据修订必须生成新 import 和新 calendar version，不修改历史记录。
- timezone 变更视为新版本变更，不能在原版本上原地修改。

## 9. 推荐落地顺序

1. 先落地 `CalendarDateRule`、`HolidayImportBatch` 和 v14 JDBC 迁移。
2. 实现 `ClasspathHolidayProvider`，先覆盖明确的 `CN` 数据包和测试年份。
3. 接入 preview/publish API 和审计日志。
4. 改造前端为法域 + 时区 + 导入抽屉 + 来源标记。
5. 再接企业批准的远程 provider，并保留 checksum/signature 校验。
6. 最后补充每个法域的官方数据包、DST、跨年和调休回归测试。
