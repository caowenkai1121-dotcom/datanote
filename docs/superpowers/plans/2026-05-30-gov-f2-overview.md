# F2：治理总览大屏（gov-f2）

## 目标
新增一屏「治理总览」看板：聚合健康分、资产、质量、工单、敏感分布，给治理中心一个第一眼可看全貌的入口。全部新建文件，不改既有 .java/.js。

## 数据源（均为现有表，标准 MyBatis-Plus BaseMapper）
- `dn_governance_score` / `HealthScoreService` —— 健康总分 + 五维分
- `dn_table_meta` —— 表数、库数(distinct database_name)、总体量(sum size_bytes)
- `dn_column_meta` —— 字段数、敏感分布(security_level / sensitive_type 分组计数)
- `dn_quality_run` —— 近期通过率(SUCCESS 的 pass_rate 均值)、近 24h 运行数
- `dn_governance_issue` —— 工单状态分布(OPEN/FIXING/CLOSED)

## 后端
### OverviewService
注入 5 个 Mapper + `HealthScoreService`。方法 `overview()` 返回嵌套 Map：
```
{
  health:   { total, dims:{规范,质量,安全,生命周期,血缘} },
  assets:   { tableCount, columnCount, dbCount, totalSizeBytes },
  quality:  { recentPassRate, runs24h },
  issues:   { open, fixing, closed },
  sensitive:{ byLevel:{...}, byType:{...} }
}
```
- 容错：每个子块独立 try-catch，数据源缺失/异常给 0 或空 Map，不抛出。
- 健康分：优先 `HealthScoreService.current()`，取 totalScore 与五维分（兼容 snapshot 的 dimScores 与即时算的 dimensions）。
- **纯函数 `recentPassRate(List<DnQualityRun> runs)`**：取 runStatus==SUCCESS 且 passRate 非空的记录，pass_rate 均值 *100，保留 1 位；无有效记录返回 0。可单测。

### OverviewController
`GET /api/gov/overview` → `R.ok(overviewService.overview())`。RequestMapping `/api/gov`。

## 单测（先红）
`OverviewRecentPassRateTest`：
- 多条 SUCCESS 求均值
- 混入非 SUCCESS / passRate 为 null 被剔除
- 空列表 / null 返回 0
- 结果按 1 位四舍五入

## 前端 gov-overview.js
注册 `window.GOV_RENDERERS.overview`。卡片网格布局，复用 gov-health.js 的 SVG 雷达风格自绘：
- 健康总分大数字 + 五维雷达
- 资产卡片：表数 / 字段数 / 库数 / 总体量(DN.fmtBytes)
- 质量：质量分(近期通过率) + 近 24h 运行数
- 工单状态分布：OPEN/FIXING/CLOSED 条形
- 敏感等级 / 类型分布：简单条形(div 自绘)
- 调 `/api/gov/overview` 一次拉全量。

## 集成点（主干需做，本分支不改 workspace.html）
1. `workspace.html` 第 3286 行附近加 `<script src="js/gov-overview.js"></script>`
2. `GOV_MODS` 数组(约 9716 行)加首项 `{ key:'overview', label:'治理总览', desc:'健康分/资产/质量/工单/敏感一屏总览' }`，置于数组最前作为默认首屏。

## 交付
- mvn 单测通过 + compile
- git commit（feat(gov-f2):）
