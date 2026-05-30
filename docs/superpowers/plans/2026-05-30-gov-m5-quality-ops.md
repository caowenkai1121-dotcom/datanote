# M5：质量调度 + 阈值 + 告警 + 趋势评分 — 实现计划

> 分支 gov-m5。对应总体设计 §7 M5（P1）。
> 原则：极简、最小改动、复用既有 dn_alert_config / 调度机制；不破坏 M1 的 executeRule 连接选择。

## 一、目标与范围

把"质量规则只能手动跑、失败判定一刀切（fail>0 即 failed）、无调度/阈值/告警/趋势"补成：

1. **阈值判定**：规则可配 `pass_threshold`（通过率阈值），实际通过率 < 阈值才判 failed。
2. **强/弱规则与维度**：`block_downstream`（强规则标记）、`dimension`（六大质量维度）落库，供后续闭环消费。
3. **调度**：接通死字段 `schedule_cron`，按分钟扫描自动执行。
4. **告警**：执行失败或低于阈值 → 复用 AlertService 多渠道告警（未启用则内部静默，无副作用）。
5. **趋势/评分**：近 20 次 run 的通过率时序 + 整体质量分（近期通过率均值 0-100）。
6. **前端**：governance.html 质量 Tab 接管渲染，显示质量分 + 趋势 + 规则列表 + 工作台链接。

## 二、关键决策

- **judgeStatus 纯函数**：`judgeStatus(passRate, threshold) -> "success"/"failed"`。passRate >= threshold 为 success。threshold 为 null 视为 100（即必须 100% 通过才算 success，保持旧"零失败才过"语义）。便于先红后绿单测。
- **total=0 通过率 100**：保持现有 fillRunResult 语义不变。
- **调度"本分钟该跑"判定**：参照 TaskSchedulerService 的 CronExpression 用法。固定 `@Scheduled(fixedDelay=60000)` 每分钟扫描，对每条规则用 `CronExpression.next(本分钟起点-1秒)` 落在本分钟区间则触发；解析失败默认不跑（避免风暴），log.warn。
- **告警复用**：注入 `com.datanote.sync.service.AlertService`，调用 `alert(ruleId, ruleName, type, message)`。AlertService 在 `datanote.alert.enabled=false` 时直接 return，零耦合零副作用；启用即多渠道发送 + 节流。不新增告警表/渠道。
- **SQL 守护**：sql/35_quality_enhance.sql 参照 sql/32，仅 `pass_threshold` 列不存在时整批 ADD 三列，幂等可重复执行。
- **前端接管**：governance.html 的 renderContent 优先调用 GOV_RENDERERS[key]，注册 quality 渲染器后即接管原 link 占位；渲染器内自带"前往工作台质量"链接。

## 三、硬约束遵守

- 不编辑 pom.xml / governance.html / sql/init-all.sql。
- 前端只新增 js/gov-quality.js（已预包含）。
- 既有 .java 仅改 QualityService.java、QualityController.java；新增 QualityScheduleService.java。
- 不破坏 M1：openConnection / isWarehouseTarget / buildSourceJdbcUrl 全部保留不动。

## 四、改动清单

| 文件 | 动作 | 说明 |
|---|---|---|
| sql/35_quality_enhance.sql | 新建 | ALTER dn_quality_rule 加 pass_threshold/block_downstream/dimension（守护幂等） |
| model/DnQualityRule.java | 改 | 加 passThreshold/blockDownstream/dimension 三字段 |
| service/QualityService.java | 改 | 抽 judgeStatus 纯函数；fillRunResult 改按阈值判定（重载传 threshold） |
| service/QualityScheduleService.java | 新建 | @Scheduled 扫描 + cron 判定 + 执行 + 告警 |
| controller/QualityController.java | 改 | 加 /trend 与 /score |
| js/gov-quality.js | 新建 | 注册 GOV_RENDERERS.quality |
| test/.../QualityJudgeStatusTest.java | 新建 | judgeStatus 纯函数单测（先红） |
| test/.../QualityScheduleDueTest.java | 新建 | cron "本分钟该跑" 纯函数单测 |
| test/.../GovernanceQualityUiTest.java | 新建 | 前端 js 注册/端点/链接断言 |

## 五、TDD 步骤

1. 写本计划。
2. 先写 QualityJudgeStatusTest（judgeStatus 不存在 → 编译红）。
3. 实现 judgeStatus + fillRunResult 改造，单测转绿。
4. 抽 QualityScheduleService 的 cron 判定为可测静态函数 isDueThisMinute，写 QualityScheduleDueTest。
5. 实现调度服务 + 告警 hook。
6. 加 Controller 端点；新建前端 js；写 UI 断言测。
7. `mvn -q -Dtest=Quality*,GovernanceQualityUiTest test` 全绿，再 `mvn -q -DskipTests compile`。
8. git commit（feat(gov-m5): 前缀）。

## 六、端点契约

- `GET /api/quality/trend?ruleId=` → `List<Map>`：近 20 次 run，字段 `startedAt`/`passRate`/`runStatus`（时间正序便于画线）。
- `GET /api/quality/score` → `Map`：`score`（近期通过率均值 0-100，无数据返回 100）、`sampleRuns`（参与计算的 run 数）。

## 七、前端 key 与集成

- 渲染器 key：`quality`，注册到 `window.GOV_RENDERERS.quality`。
- 调用端点：`/api/quality/score`、`/api/quality/rules`、`/api/quality/rule/{id}/runs`（趋势复用既有 runs 或新 /trend）。
- 提供 `workspace.html#/quality` 链接。

## 八、风险

- 调度与工作台手动执行并发：executeRule 各自独立插 run 记录，无共享可变状态，无需额外锁。
- cron 解析失败：默认不触发并 log.warn，避免错误表达式导致每分钟风暴。
