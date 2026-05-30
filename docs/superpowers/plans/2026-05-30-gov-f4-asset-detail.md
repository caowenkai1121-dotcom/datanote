# F4：资产详情增强 实施计划

- 日期：2026-05-30
- 分支/worktree：gov-f4（D:\data\datanote\.claude\worktrees\gov-f4）
- 目标：在“资产目录”模块上叠加“资产详情”能力——字段级元数据、Profiler 探查、术语表（Glossary）维护、查看血缘。

## 背景与约束

- 入口：workspace.html#/governance 的“资产目录”tab，渲染器 `window.GOV_RENDERERS.assets`（在 `js/gov-assets.js`）。
- 元数据来源：`dn_table_meta`（表）+ `dn_column_meta`（字段：列名/类型/键/可空/注释/密级 securityLevel/敏感类型 sensitiveType）。
- Profiler 下推数仓：复用 `DataMapService.profile` 思路，自连 `HiveConfig.getConnection()`（Doris/MySQL 协议），逐字段统计空值率/distinct，限字段数防慢。
- 统一返回 `R<T>`，前端 `DN.*`（dn-common.js）解析 code===0。
- 硬约束：禁改 pom.xml / workspace.html / governance.html / sql/init-all.sql；前端只改 `js/gov-assets.js`（已被 workspace.html 加载，不新增 JS 文件以免无法被引入）；不碰 gov-lineage.js 等其他模块及其 Controller/Service。

## 后端（新建文件）

1. `sql/44_glossary.sql`：`dn_glossary_term`（id, term, alias, definition, category, created_at），`CREATE TABLE IF NOT EXISTS`，InnoDB/utf8mb4，term 建普通索引。
2. `model/DnGlossaryTerm.java`：MyBatis-Plus 实体，`@TableName("dn_glossary_term")`。
3. `mapper/DnGlossaryTermMapper.java`：`extends BaseMapper<DnGlossaryTerm>`。
4. `service/AssetDetailService.java`：
   - `assetDetail(db, table)`：按 db+table 定位 `dn_table_meta`，取其字段 `dn_column_meta`（按 ordinal/id 排序），聚合返回 `{table, columns}`。
   - `profile(db, table)`：自连 hiveConfig，先 `COUNT(*)` 总行数，再逐字段（限 `MAX_PROFILE_FIELDS=30`）统计 null_count/distinct，输出 nullRate（纯函数 `formatRate`）。
   - Glossary CRUD：list/save/delete。
   - 纯函数静态方法 `formatRate(nullCount, total)`、`limitFields(size, max)` 便于单测。
5. `controller/AssetDetailController.java`（`/api/gov/asset`）：
   - `GET /detail?db=&table=`
   - `GET /profile?db=&table=`
   - `GET /glossary`、`POST /glossary`、`DELETE /glossary/{id}`

## 前端（仅改 js/gov-assets.js）

- 资产列表行追加“详情”操作列；点击在该表下方展开/收起一个 detail 区块：
  - 字段级元数据表（列名/类型/键/可空/注释/密级标签/敏感标签）。
  - “探查”按钮 → 调 `/profile`，渲染总行数 + 各字段空值率/distinct。
  - 术语表区：列出 `/glossary`，支持新增（term/category/definition）与删除。
  - “查看血缘”：内联调用 `/api/lineage/table-edges?db=&table=` 展示上下游表（不跳转、不改血缘模块）。
- 保留现有：采集按钮、采集日志、资产列表、M10 生命周期/无用表/成本三小节。

## 测试

- `AssetDetailServiceTest`：纯函数 `formatRate`（含 total=0、四舍五入）、`limitFields`（不超上限/小于上限原样）。
- 不引入 DB 集成测试，避免依赖真实 Doris。

## 验证

- `mvn -q -Dtest=AssetDetailServiceTest test` 通过。
- `mvn -q -DskipTests compile` 通过。
- git commit（feat(gov-f4):）。
