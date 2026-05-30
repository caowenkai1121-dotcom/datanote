# M10：资产盘点 + 生命周期 + 成本 + 无用表识别

日期：2026-05-30
分支：gov-m10（基于 master，含 P0+P1）
设计依据：`docs/superpowers/specs/2026-05-30-data-governance-design.md` §7 / P2-2、M10。

## 目标
把"资产盘点 + 生命周期编排 + 成本估算 + 无用表识别与回收"补成可用闭环：
- 资产快照采集（体量/行数/最近访问 → 成本）。
- 生命周期策略 CRUD，应用策略自动下发 **Doris 原生 DDL**（dynamic_partition / storage policy / ALTER）；下发失败降级为 PENDING + 日志，不崩。
- 无用表四要素打分（久未访问 + 体量 + 无下游血缘 + 无任务引用），纯函数可单测。
- 销毁三道护栏：①血缘影响校验（有下游边禁止）②软删宽限期（默认 30 天，可配）③审批留痕。绝不直接物理删表。
- 成本：可配单价 × size 估算 + 排行。

## 硬约束（不可触碰）
- 禁改 pom.xml、governance.html、sql/init-all.sql。
- 既有 Java 全部不改，仅新增文件；下发 Doris DDL 用 `hiveConfig.getConnection()` 自执行，不改 HiveService/HiveConfig。
- 前端只在 `js/gov-assets.js` **追加**生命周期/无用表/成本小节，保留既有采集 + 资产列表。
- 新 SQL 用 `sql/40_lifecycle.sql`（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）。

## 数据模型（sql/40_lifecycle.sql）
- `dn_lifecycle_policy`：id, db_name, table_name, policy_type(HOT_COLD/TTL/ARCHIVE), cold_days, ttl_days, enabled, status(ACTIVE/PENDING/FAILED/DROP_PENDING/DROPPED), ddl_text, last_msg, created_at, updated_at。
  - 复用同表承载"销毁宽限期"：markForDrop 写一条 policy_type=ARCHIVE? 不——销毁单独走 status=DROP_PENDING + drop_due_at + approver/reason 字段。
- `dn_asset_stat`：id, table_meta_id, db_name, table_name, size_bytes, row_count, last_access_at, cost_estimate, collected_at。
- 系统配置（复用 dn_system_config 键值）：`lifecycle.cost.unit_price`（元/GB/月）、`lifecycle.drop.grace_days`（默认 30）、`lifecycle.unused.access_days`（默认 90）。

## 纯函数（先写单测，先红）
`LifecycleScorer`（独立纯函数类，无 Spring）：
- `scoreUnusedTable(long lastAccessDays, long sizeBytes, boolean hasDownstreamLineage, boolean hasTaskRef)` → `UnusedScore{score(0-100), candidate}`。
  - 四要素加权：久未访问（越久分越高）、体量（越大越值得回收）、无下游血缘（+大权重）、无任务引用（+权重）。
  - candidate = 无下游血缘 && 无任务引用 && lastAccessDays≥阈值 && score≥阈值。
- `estimateCost(long sizeBytes, double unitPricePerGbMonth)` → 月成本（GB × 单价）。
- `dropDueAt(LocalDateTime markedAt, int graceDays)` → 到期时间（便于测）。

单测：`LifecycleScorerTest`（service 包或 util 包，遵循现有 service 包纯函数测试惯例，如 StandardCheckTest）。

## Java（全新文件）
- `model/DnLifecyclePolicy.java`、`model/DnAssetStat.java`（@TableName + lombok @Data）。
- `mapper/DnLifecyclePolicyMapper.java`、`mapper/DnAssetStatMapper.java`（BaseMapper）。
- `service/LifecycleScorer.java`（纯函数 + 内部 DDL 构造纯函数 `buildDorisDdl(policy)`，也可单测）。
- `service/LifecycleService.java`：
  - 策略 CRUD（listPolicies/savePolicy/deletePolicy/togglePolicy）。
  - `applyPolicy(id)`：构造 Doris DDL → `hiveConfig.getConnection()` 执行；成功 status=ACTIVE，失败 catch → status=PENDING + last_msg=异常，不抛崩溃。
  - `collectStats()`：遍历 dn_table_meta 生成 dn_asset_stat 快照（size/row 复用 meta；last_access_at 暂用 last_collected_at 兜底；cost = scorer.estimateCost）。
  - `unusedTables()`：对每张表查下游血缘（复用 LineageEdgeService.tableNeighbors/impact 思路，直接查 dn_lineage_edge）+ 任务引用（dn_sync_job? 简化为是否出现在血缘 job 或同步目标），调 scorer 打分，返回候选清单。
  - `markForDrop(id, approver, reason)`：①血缘影响校验：有下游边 → 拒绝（抛业务异常）②写 status=DROP_PENDING + drop_due_at=now+grace ③审计 last_msg/approver/reason。绝不物理删。
  - `executeDueDrops()`：到期（drop_due_at≤now 且 status=DROP_PENDING）才真正 DROP TABLE（经 hiveConfig），成功 status=DROPPED，失败 status=FAILED。
  - `costRanking(limit)`：按 cost_estimate 倒序排行。
- `controller/LifecycleController.java`：`/api/gov/lifecycle/*`
  - GET policies、POST policies、DELETE policies/{id}、POST policies/{id}/toggle、POST policies/{id}/apply
  - POST stats/collect、GET unused、POST drop（markForDrop）、POST drop/execute、GET cost

## 前端（追加 gov-assets.js）
在 `GOV_RENDERERS.assets` 末尾追加三块容器与 loader：
- 生命周期策略：列表 + 新增表单 + 应用按钮（显示 status）。
- 无用表识别：触发采集 + 列出候选（分数/四要素），含"标记销毁"按钮（弹审批人/原因）。
- 成本排行：表格。
保留既有 `loadAssets`。

## 验证
1. `mvn -q -Dtest=LifecycleScorerTest test` 先红后绿。
2. `mvn -q -DskipTests compile` 全量编译通过。
3. git commit（feat(gov-m10): 前缀）。

## 风险与降级
- Doris 未配冷存后端：applyPolicy 下发 storage policy 必失败 → 捕获标 PENDING，前端显示"待 Doris 冷存就绪"，不影响其他功能。
- 物理删表风险：三道护栏 + 仅 executeDueDrops 到期执行，markForDrop 永不删。
