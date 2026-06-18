# AI Agent 深度优化 500 轮程序

> 业主 2026-06-18 令"深度参考全网/GitHub 开源,深度优化 agent 智能度与功能",迭代 500 轮。
> 每轮一项**真实**增强(研究路线 + 衍生),逐项编译+测试,批量部署 38.76.183.50,本文件记录每轮。不灌水。
> 调研出处见记忆 [[ai-agent-sota-optimize]];前序已交付: Tool RAG 语义工具发现 / CRITIC 写后真值自校验 / 连续错误熔断。

## 基线能力(优化前)
ReAct 循环 + 并行工具 + think 留痕 + todo/plan + steering + ask_user + ContextCompressor(LLM摘要) + ErrorClassifier + IterationBudget(16步/墙钟300s) + delegate_task 子代理 + cron 自治 + reflectIfNeeded 内省反思 + 向量记忆/RAG/GraphRAG + Guardrail三态+审批 + read_tool_result + 自学习记忆/skill + 渐进工具披露(阈值50) + cleanFinal 防泄漏。

## 轮次记录

### 批 A(R1-R3)— 上下文与目标对齐
- **R1 三段式 Condenser**(OpenHands keep_first + lost-in-the-middle): ContextCompressor 由"摘要头+保尾"升级为"**保首K(初始计划/发现 primacy)+ 摘要中段 + 保尾(recency)**",只压被遗忘的中段。HEAD_KEEP_CHARS=1500,中段不足则退化原行为。
- **R2 目标末尾复述**(Manus recitation): PromptBuilder 在长 trace(>4000字符)末尾重述目标 + 收敛自问指令,让目标始终在 recency 区,防长任务偏题/空转。
- **R3 tool_search 语义发现纪律**: PROTOCOL 规则1 改写,明示"清单未列出的工具用 tool_search 按意图发现(返回 name+params 直接调用)",提升渐进披露下的工具发现率,配合已上线的语义 tool_search。
- **R4 周期性规划检查点**(smolagents planning_interval): 主循环每 PLAN_INTERVAL=6 生产步注入"暂停审视目标/已知事实/剩余步骤(todo update)、判断收口或偏题"提示,防长任务漂移空转;与 R2 末尾复述互补(R2 每轮对齐目标, R4 周期性强制重规划)。

### 批 B(R5-R6)— 工具反馈与写后验证(SWE-agent ACI + CRITIC 强化)
- **R5 空结果显式标注**(SWE-agent ACI): appendTrace 对 ok 但空(null/空Map/空集合/空串)的工具结果显式写"(空结果/无匹配数据, 勿臆造, 换条件或如实告知)", 防 agent 对空结果臆造内容或反复重查。
- **R6 写后验证纪律**(prompt 层强化 CRITIC): PROTOCOL 诚实纪律加 ⑤ "写操作执行后、报告成功前, 尽量调只读工具(asset_detail/table_profile/quality_score)核实真实结果, 不仅凭返回 ok 断言成功"; 与已上线的 CRITIC trace 注入双保险(代码注入 + prompt 纪律)。

> 业主 2026-06-18 追令: 轮次扩至 **2000 轮**, 全程自主(自定方向/自审批/自推进), agent 潜力变小可转向任意模块(前后端皆可)。

## 路线储备(后续轮次取材)
LLM Condenser✓(R1) / 目标复述✓(R2) / 验证回写飞轮(approved样本→向量金样本) / inline security_risk 自评 / EventLog 可重放+checkpoint / 语义层上下文选择器(Neo4j血缘+PageRank选喂哪些表字段) / 周期性强制规划步(smolagents planning_interval) / 工具 ACI 反馈格式统一(SWE-agent: 空结果文案/大结果分页/写后校验) / 结构化输出约束 / self-consistency 多采样投票(高价值只读决策) / 工具结果按类型定向摘要 / 子代理 context-folding / 经验 insight 蒸馏(ExpeL) / A-MEM 记忆图召回 / 模型档位按任务难度自适应路由。
