# DataNote AI Agent —— 天工·自由意志数据智能体(天工司辰)架构蓝图

> 七角色协作产出(Workflow 13 agent 调研→判审团设计→业务校验→落地蓝图)。
> 借鉴 HKUDS/CLI-Anything（窄接口+结构化输出+内省一等公民+会话快照）与 NousResearch/hermes-agent（自主推理主循环+IterationBudget+Steer 转向通道+Hermes function-calling 协议），融合《天工开物》(工具链编排/逐道工序详记/务实)与自由意志(自主规划/可观测/可中断/人机协同)。

## 一、定位（Architect 守门人定稿）
- **窄接口 + 规划-执行解耦 + 全程可审计 + 人机协同**的领域智能体。
- 骨架取方案 C（天工开物·自由意志），循环主干 graft 方案 B（规划-执行解耦），纯 ReAct 仅作 replan 兜底。
- **不替代既有 domain 服务**，而把它们包成一组薄适配器工具（`AiTool`），LLM 在护栏内自主串联。
- LLM 调用全程只走 `AiAssistService.chat(userMessage, context)` 单入口，**不改其签名**；多轮历史与工具清单由 Agent 在 Java 侧拼进 `context`。
- 协议用 Hermes 式 `<tool_call>{json}` 自描述；Java 用正则 + Jackson 容错抠解析（复用 `extractSqlBlock` 思路）。
- **弃用 SSE（全库零先例）**，改 DB 标志位（`interrupt_flag`/`steer_text`）+ `/progress` 轮询实现可观测/可中断/可干预。

## 二、天工开物 × 自由意志 → 架构映射
| 思想 | 落地机制 |
|---|---|
| 天工开物·工具链编排 | `AiToolRegistry` 把领域能力建模为可组合工具；规划期串成 SkillPipeline |
| 天工开物·逐道工序详记 | `dn_ai_step` 三合一记录（消息流+工具调用+计划），每步带 rationale/Artifact，可回放 |
| 天工开物·务实/最小够用 | 零新第三方依赖；只暴露真实存在的服务方法；内省（只读）工具一等公民 |
| 自由意志·自主规划 | Planner 一次产出整条流水线（M3）；ReAct replan 兜底 |
| 自由意志·可观测 | `/progress` 轮询 thought/计划/已完成步增量 |
| 自由意志·可中断/可干预 | `interrupt_flag` 优雅停机 + `steer_text` 中途转向（step 边界 drain） |
| 自由意志·人机协同边界 | 写动作护栏三态（ALLOW/DENY/NEED_APPROVAL）+ 审批门留痕 |

## 三、业务专家裁决（一票否决 → 必改清单，已并入设计）
1. 删除虚构工具 `golden_publish`（`MdmPublishService` 实测只有 `fanOut/normalizeChangeTypes`，无 publish）。
2. 真删表入口 `executeDueDrops/markForDrop/deletePolicy` **排除出 agent 写通道**，agent 只能产出"待销毁建议"，绝不执行 DDL。
3. 审计 **fail-closed**：写工具执行前先写审计，审计入库失败则拒绝执行（`AuditService.record` 仅 `log.warn` 不抛 → Agent 层回查兜底）。
4. 写动作**默认逐步确认**，"计划预览整批放行"降级为可选开关（默认关）。
5. 自学习 memory/skill **永不**影响护栏/权限/工具危险级别，只作用于只读探查。
6. 凭据/权限/RBAC/部署/改护栏配置类操作**永不**做成 agent 工具（禁区清单写死）。

## 四、Agent 主循环（17 步，M1 实现 1-10/13/15/17，M2 补 11/12/14，M3 补 4/16 转向与 replan）
1. loadOrInit 恢复会话 → 2. 降级守门 `isAvailable()` → 3. budget.init（maxSteps/maxWallSec）→ 4. 感知 drain interrupt/steer → 5. 循环边界判定 → 6. PromptBuilder 三层拼装 → 7. 调 `chat`（识别降级前缀即停机）→ 8. parseToolCalls/extractJson 容错解析 → 9. 终答判定 → 10. Validation 粗校验 → 11. Guardrail(M2) → 12. 写前 fail-closed 审计(M2) → 13. 执行 safeInvoke → 14. 回读校验 verify(M2) → 15. observe+落 `dn_ai_step` → 16. 反思 replan(M3) → 17. persist 终答+全量 trace。

## 五、数据模型（dn_ai_ 前缀，sql/70_ai_agent.sql）
- **dn_ai_session**（M1）：会话主表 — session_id/user_name/goal_intent/status/interrupt_flag/steer_text/plan_json/budget_steps_used/version/时间。
- **dn_ai_step**（M1）：三合一 — seq/step_type(PLAN/SKILL_CALL/REPLAN/FINAL)/role/content/think_content/skill_name/skill_group/args_json/result_status/result_type/result_data/read_only/risk_level/latency_ms。文本入库前过 `sanitize` 剥 NUL/控制字符。
- **dn_ai_approval**（M2）：高危/写动作审批留痕（审批人/时间/参数不可空）。
- **dn_ai_memory_skill**（M4 延后）：自学习经验/技能（红线：永不提权）。

## 六、工具清单（名 → 背后真实服务，已核实签名）
**M1 只读（readOnly=true, risk=LOW）**
- `gov_overview` → `OverviewService.overview()`（无参，治理总览全量）
- `quality_score` → `HealthScoreService.current()` + `QualityService.failureAnalysis(ruleId)`
- `lineage_impact` → `LineageEdgeService.impact(db,table)`（L89 下游 BFS）

**M2 只读续**：lineage_trace / metadata_search / metadata_databases / metadata_tables / sync_job_get / sync_dashboard_summary / sync_precheck / asset_profile / **lifecycle_drop_suggest（只读，绝不调 executeDueDrops）**

**M2 写 MEDIUM（默认逐步确认 + fail-closed 审计 + 回读校验）**：quality_run_rule / sync_job_run / sync_job_reconcile / quality_issues_sync / health_create_issue / classification_confirm

**M2 写 HIGH（强制单步审批落 dn_ai_approval，逐个不可批量）**：cdc_start / scheduler_backfill(`BackfillService.executeBackfill` L77) / metadata_crawl_all(`MetadataCrawlerService.crawlAll` L65)

**永久禁区（绝不做成工具）**：删表 DDL / 凭据 / RBAC 权限 / 部署 / 改护栏配置。

## 七、落地约束（外科式增量）
- 全部新增代码落 `com.datanote.platform.ai.agent.*`，**不重构任何既有 domain 服务**。
- **唯一 Java 侵入点**：`DataNoteApplication` 的 `@MapperScan` 数组末尾追加 `"com.datanote.platform.ai.agent.mapper"`（否则新 BaseMapper 不被扫描，应用启动失败 —— 三方案共同遗漏的关键细节）。
- 零新第三方依赖：参数校验 Jackson 手写 required/type 粗校验；JSON 解析用已注入 `ObjectMapper`。
- 前端：workspace.html 加 `assistant` 顶级路由 + `viewAssistant` 容器 + `ai-agent.js`（纯 DN.* 组件，无框架，保 NUL 字节/深链）。

## 八、里程碑
- **M1（本轮）**：骨架 + 只读最小闭环。2 表 + 工具层 + 3 只读工具 + 单步/多步顺序循环 + 3 端点 + 最小对话面板。零写副作用、零安全风险。518 测试绿 + 本地 E2E。
- **M2**：写工具 + 三道护栏 + 审批门 + fail-closed 审计（落实全部安全红线）。
- **M3**：规划-执行解耦 + 多轮 + 中断/转向轮询（@Async + /progress/interrupt/steer）。
- **M4**：自学习复盘（延后）+ 审批/可观测/覆盖缺口 UI 完善。

## 九、M1 验收 E2E
启动后访问 `#/assistant`，问"看下治理总览，再查 dwd_order 的下游影响"，观察 agent 自主串 `gov_overview→lineage_impact`、对话流出两张工具卡 + 终答摘要，`dn_ai_step` 落 SKILL_CALL×2 + FINAL×1 可回放，后端无报错；AI 未配置时面板优雅提示不卡死。

## 十、待决问题（M2+ 编码前确认）
- 写工具逐步确认是否给运维"会话级一次授权（仍逐条留痕）"快捷开关（业务专家倾向严格）。
- `cdc_start` 真实 Service 方法/签名需出"工具→方法"对照表（防第二个 golden_publish 式虚构）。
- fail-closed 审计实现位置：Agent 层 record 后 selectCount 回查 vs 给 AuditService 增 recordStrict（触碰既有服务，权衡外科红线）。
- `available(role)` 角色裁剪从何上下文取当前角色 + 无角色默认工具集。
- M3 @Async 并发：同用户多标签页会话是否限流/单飞，防预算滥用。
- M4 memory/skill"只作用只读"硬约束：Guardrail 增"memory 触发步不得为写工具"硬校验（倾向）。
