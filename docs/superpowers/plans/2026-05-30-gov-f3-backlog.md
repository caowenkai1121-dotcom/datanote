# gov-f3：补齐待办（后端为主 + 主题域UI）

分支 `gov-f3`，隔离 worktree。技术栈 Java8 + SpringBoot2.7 + MyBatis-Plus + MySQL/Doris；前端 vanilla JS。

## 范围（4 项）

### 1. 主题域管理 UI + 级联删除修复
- 现状：`SubjectController` 已有 `/api/subject/list`、`/tree`、`POST`（创建）、`PUT/{id}`（更新）、`DELETE/{id}`（删除）。
  但 `delete` 直接调 `subjectMapper.deleteById(id)`，**未走 `SubjectService.delete` 的级联逻辑** ——
  `SubjectService` 全类是死代码（无任何调用方），删一级主题会留下孤儿子主题。
- 改：`SubjectController` 注入 `SubjectService`，`delete` 改为调 `subjectService.delete(id)`（带 `@Transactional` 级联删子节点）。
- 新建 `js/gov-subject.js`：注册 `window.GOV_RENDERERS.subject`，主题域树展示 + 增（含选父级）/删（级联，二次确认）/改。复用 `DN.*` 与 `gov-standard.js` 风格。

### 2. 指标-资产关联
- 新 SQL `sql/43_metric_ref.sql`：建 `dn_metric_ref(id, metric_id, db_name, table_name, column_name, ref_type, created_at)`。
- 新实体 `DnMetricRef` + Mapper `DnMetricRefMapper`（MyBatis-Plus BaseMapper，沿用 `@MapperScan("com.datanote.mapper")`）。
- `MetricController` 加端点：
  - `GET  /api/metric/{id}/refs`  查指标关联
  - `POST /api/metric/{id}/refs`  新增关联
  - `DELETE /api/metric/refs/{refId}`  删关联

### 3. 健康分快照定时
- `HealthScoreService` 已有 `computeAndSnapshot()`（计算并入库 `dn_governance_score`）。
- 加 `@Scheduled(cron = "0 30 1 * * ?")` 每日 1:30 调用之（主程序已 `@EnableScheduling`）。

### 4. 不带库名脱敏覆盖
- 根因：`SqlMaskRewriter.rewrite(sql, defaultDb, ...)` 本就支持用 `defaultDb` 补全裸表名（`parseQualified` → `TableRef(dft, table)`），
  且单测 `defaultDb_filled_columnMatched` 已验证。但 `HiveDdlController` 两处调用（`applyMasking`、`injectRowFilters` 网关）传的是 `""`，
  导致裸表 `users` 归为库 `""`，与策略库 `ods.users` 不匹配 → 漏脱敏。
- 改（保守，最小改动）：`HiveDdlController` 注入 `@Value("${doris.database:}")` 默认库，
  把 `applyMasking` 里 `rewrite(sql, "", ...)` 改为 `rewrite(sql, defaultDb, ...)`。
  不动 admin/单用户/`data:unmask` 绕过路径，不动 fail-closed 语义。

## 交付物
- 计划：本文件。
- 单测：`SqlMaskRewriterTest` 已覆盖默认库补全（无需新增；纯函数行为未变，仅修调用端入参）。
- 编译：`mvn -q -DskipTests compile` 通过；`mvn -q -Dtest=SqlMaskRewriterTest test` 通过。
- 提交：`feat(gov-f3): ...`。

## 主干集成注意（workspace.html 不可编辑，需主干手工接线）
- `<head>` 脚本区（约 3285 行）追加：`<script src="js/gov-subject.js"></script>`。
- `GOV_MODS` 数组（约 9717 行）追加：`{ key: 'subject', label: '主题域', desc: '主题域树管理与层级维护' }`。
  侧边栏 `govSidebar` 由 `GOV_MODS` 自动渲染，`switchGovModule` 据 key 调 `GOV_RENDERERS.subject`。
