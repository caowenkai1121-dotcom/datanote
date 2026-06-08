# AI Agent 天工司辰 —— 深度融入全系统 集成蓝图

> 七角色 Workflow(9-agent: 5全域调研 + 业务/产品深挖 + 架构整合 + 蓝图)产出。让 AI 从"独立 tab"变成"无处不在的情境智能"：**一个内核 / 三种唤起 / 一条回链**。

## 核心架构
- **唯一会话内核** = `ai-agent.js` + `/api/ai/agent/chat`（多步循环复用 `AiAssistService.chat` 单底座）。
- **三种唤起**：(A) header 全局唤起器 + Ctrl+K；(B) 各模块情境按钮；(C) 旧 AI 触点转交。
- **一条回链** = 工具结果 `_deeplink` + 终答 `[表:库.表]/[规则:#id]/[任务:#id]` token → `navigateTo` 跳模块 → navBackChip 一键回 AI助手。
- **消重铁律**：单轮确定性任务留旧(develop nl2sql/explain/optimize、datamap ai-search)，多步开放问答归 agent；旧的不并入、改"转交/深析"联动；严禁再给 AiAssistController 加端点、严禁任何模块自建第四套对话框。

## 统一上下文协议（复用 __navCtx，仅加 3 字段）
- 入站：`navigateTo('assistant', {agentPrefill, agentCtx, agentAutoSend})`；ROUTES.assistant.init 读 __navCtx 透传 `initAiAssistant(opts)`。
- `agentCtx` 字段全集：`{route(必填), db?, table?, gov?, ruleId?, issueFilter?, jobId?, jobName?, runId?, taskId?, taskType?, taskName?, date?, currentSql?, metricId?}`。
- 双保险注入：①前端 prefill 成 userMessage 喂 LLM；②后端 `AgentContext.bizCtx` → `PromptBuilder` 注入「# 当前所在场景」段（工具缺参可从 bizCtx 回退）。
- 回链 ctx 必须用既有键：catalog→`{openTable:{db,table}}`；governance→`{gov,table:{db,table}}`；dbsync→`{openDetail:jobId}`；metrics→`{editId}`；settings→`{sm}`。

## 全局唤起器
- header-right `#aiLauncherBtn`（user-btn 前）；`window.openAiLauncher(prefill,ctx)` + 别名 `window.openAiAgent`；按 currentRoute 拼默认 ctx。
- Ctrl+K 全局化（非 project/dbsync、无输入焦点时 → openAiLauncher）。
- 体验底线：绝不主动弹窗/不右下 FAB；全局唤起 autoSend=false；AI 未配置走 blocked 优雅降级。

## 里程碑（深度集成子程序）
- **M0（已交付 第57轮）**：上下文协议基座 — AgentContext.bizCtx + Controller 收 ctx + PromptBuilder「# 当前所在场景」+ AiAgentService.buildBizCtxText + ai-agent.js initAiAssistant(opts)/send 带 ctx + ROUTES.assistant.init 读 __navCtx。**E2E 实证：message 无表名仅靠 ctx 注入，agent 即用 {db,table} 调工具。**
- **M1（已交付 第57轮）**：全局唤起器三件套 — header AI 按钮 + openAiLauncher/openAiAgent + Ctrl+K 全局；toolCard `_deeplink` 跳转按钮 + assistantBubble token→可点 chip(XSS 安全 DN.h text)；数据地图表详情「🤖 AI 分析此表」P0 情境入口(dmAskAi)。+ 6 个已核实只读工具(asset_detail/table_profile/lineage_trace/lineage_graph/health_trend/issue_stats)。
- **M2**：治理域 P0/P1 入口 + 治理只读工具补全(sensitive_scan/classification_audit/sensitive_heatmap/issue_by_rule/issue_leaderboard/health_compute/standard_*)。
- **M3**：同步/运维/开发 P0 入口 + 工具(sync_job_detail/sync_recon_check/sync_checksum/sched_run_log/sched_today_status/sched_downstream_tree/sql_parse_tables/sql_explain/sql_optimize)。护栏：SchemaDrift 禁工具化；重试/补数/暂停不代办。
- **M4**：首页今日 AI 简报/一键体检 + 旧触点(develop/datamap-ai-search)「转交智能体」联动收口。
- **M5**：P1 入口批量 + 审计/标准/指标工具(可选)。**SysMetricsTool 暂缓(背后 Service 待二次核实, 严禁臆造)**。

## 工具→真实 Service 方法 对照（M2-M5, 已 Grep 核实, 防 golden_publish 式虚构）
sensitive_scan→ClassificationService.scanTable(db,table) L195; classification_audit→auditTrail L121; sensitive_heatmap→sensitiveHeatmap L79; issue_by_rule→IssueService.listByQualityRule(ruleId) L430; issue_leaderboard→leaderboard L124; health_compute→HealthScoreService.compute L264; sync_job_detail→SyncJobService.getById L69 + getCheckpoints L283; sync_recon_check→DataReconciliationService.reconcile L40; sync_checksum→checksum L84; sched_run_log→TaskSchedulerService.getRunLog L595; sched_today_status→getTodayStatus L523; sched_downstream_tree→TaskDependencyService.getDownstreamTree L231; sql_parse_tables→parseSQLTables L269; sql_explain→AiAssistService.explainSql L197; sql_optimize→optimizeSql L205; standard_top_violations→StandardService.topViolations; standard_recent_runs→recentRuns。

## 永久禁区（绝不工具化）
删表 DDL(executeDueDrops/markForDrop/deletePolicy 仅产"待销毁建议") / 凭据 / RBAC 权限 / 部署 / 改护栏配置。写工具(M2 起)走 Guardrail 三态 + 审批 + fail-closed 审计。
