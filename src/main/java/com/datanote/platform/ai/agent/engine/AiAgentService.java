package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
import com.datanote.platform.ai.agent.model.DnAiSession;
import com.datanote.platform.ai.agent.model.DnAiStep;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolRegistry;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 天工·自由意志数据智能体 主循环（M1：单工具/步 顺序循环，零写副作用）。
 * 感知→构造prompt→调LLM→解析tool_call→校验→执行只读工具→回灌→直到终答或达步数上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {

    private final AiAssistService aiAssistService;
    private final AiToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final DnAiSessionMapper sessionMapper;
    private final DnAiStepMapper stepMapper;
    private final com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;
    private final com.datanote.platform.audit.AuditService auditService;
    private final ApprovalGate approvalGate;
    private final AiMemoryService aiMemoryService;

    /** M1 单轮最大步数（含工具调用），防失控 */
    private static final int MAX_STEPS = 6;
    /** trace 中单条结果摘要上限，控制 token */
    private static final int TRACE_RESULT_CAP = 1500;
    /** 入库 result_data 上限 */
    private static final int STORE_RESULT_CAP = 8000;
    /** RAG 召回条数 */
    private static final int RAG_TOPK = 5;
    /** RAG 注入文本上限(控 token) */
    private static final int RAG_TEXT_CAP = 800;
    /** 自学习记忆召回条数 */
    private static final int MEM_TOPK = 3;

    /** 一轮对话：发起 userMessage，返回 {sessionId, status, finalAnswer, steps:[...]}。 */
    public Map<String, Object> run(String sessionId, String userMessage, AgentContext ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();

        if (userMessage == null || userMessage.trim().isEmpty()) {
            resp.put("status", "error");
            resp.put("finalAnswer", "请输入你的问题。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }

        // 降级守门：AI 未配置直返提示，不进循环
        if (!aiAssistService.isAvailable()) {
            DnAiSession s = loadOrInit(sessionId, userMessage, ctx);
            resp.put("sessionId", s.getSessionId());
            resp.put("status", "blocked");
            resp.put("finalAnswer", "AI 功能未配置。请在【系统配置 → AI 配置】中设置 API Key 后再使用智能体。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }

        DnAiSession session = loadOrInit(sessionId, userMessage, ctx);
        AgentState st = new AgentState();
        st.session = session;
        st.seq = nextSeq(session.getSessionId());

        // 多轮历史：把既往 FINAL 摘要 seed 进 trace
        seedHistory(st);

        List<DnAiStep> newSteps = new ArrayList<>();
        // 记录用户消息
        newSteps.add(writeStep(st, "USER", "user", userMessage, null, null, null, null, null, null, true, "LOW", null));

        String manifest = toolRegistry.toToolsManifestJson();
        String today = LocalDate.now().toString();
        String bizCtxText = buildBizCtxText(ctx == null ? null : ctx.getBizCtx());
        String ragText = buildRagText(userMessage);   // 循环外算一次, 自动 grounding
        String memoryText = aiMemoryService.recall(userMessage, ctx == null ? null : ctx.getUserName(), MEM_TOPK); // 自学习记忆召回(只读上下文)
        boolean first = true;

        for (int i = 0; i < MAX_STEPS && !st.done && !st.blocked && !st.awaitingApproval; i++) {
            String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText);
            String userPrompt = first
                    ? userMessage
                    : "请根据上面的『已执行步骤与工具结果』继续：若仍需信息就只输出一个 <tool_call>，否则直接给出最终中文答复（不要再输出 tool_call）。";
            first = false;

            long t0 = System.currentTimeMillis();
            String raw = aiAssistService.chat(userPrompt, context);
            long latency = System.currentTimeMillis() - t0;

            if (raw == null || raw.startsWith("AI 功能未配置") || raw.startsWith("AI 请求失败") || raw.equals("AI 返回格式异常")) {
                st.blocked = true;
                st.blockReason = raw;
                newSteps.add(writeStep(st, "FINAL", "assistant", raw == null ? "AI 调用失败" : raw,
                        null, null, null, null, "error", "exec_failed", true, "LOW", latency));
                break;
            }

            List<String> toolJsons = AgentTextUtil.parseToolCalls(raw);
            if (toolJsons.isEmpty()) {
                // 终答
                st.finalAnswer = AgentTextUtil.sanitize(raw);
                st.done = true;
                newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer,
                        null, null, null, null, "ok", null, true, "LOW", latency));
                break;
            }

            // M1：取首个工具调用执行
            String callJson = toolJsons.get(0);
            String toolName = null;
            JsonNode argsNode = null;
            try {
                JsonNode call = objectMapper.readTree(callJson);
                toolName = call.path("name").asText(null);
                argsNode = call.get("arguments");
            } catch (Exception e) {
                st.trace.append("步骤").append(st.seq).append(" 解析工具调用失败，请输出合法 JSON。\n");
                newSteps.add(writeStep(st, "SKILL_CALL", "assistant", AgentTextUtil.sanitize(raw),
                        null, null, callJson, null, "error", "bad_arguments", true, "LOW", latency));
                continue;
            }

            AiTool tool = toolRegistry.find(toolName);
            if (tool == null) {
                AiToolResult r = AiToolResult.fail("unknown_tool", "未知工具: " + toolName);
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "unknown_tool", true, "LOW", latency));
                continue;
            }

            String vErr = Validation.validate(argsNode, tool.paramsSchemaJson(), objectMapper);
            if (vErr != null) {
                AiToolResult r = AiToolResult.fail("bad_arguments", vErr);
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "bad_arguments", tool.readOnly(), tool.risk().name(), latency));
                continue;
            }

            // ===== 写操作护栏门(咽喉点): 只读直放 / 红线拒 / 写需审批 =====
            Guardrail.Gate gate = Guardrail.gate(tool);
            String riskName = tool.risk() == null ? "HIGH" : tool.risk().name();
            if (gate == Guardrail.Gate.DENY) {
                AiToolResult r = AiToolResult.fail("forbidden", "该操作属永久禁区, 拒绝执行");
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "forbidden", tool.readOnly(), riskName, latency));
                continue;
            }
            if (gate == Guardrail.Gate.NEED_APPROVAL) {
                ApprovalGate.Outcome oc = approvalGate.check(st.session.getSessionId(), st.seq, tool, argsToStr(argsNode), Guardrail.isHigh(tool));
                if (oc == ApprovalGate.Outcome.PENDING) {
                    st.awaitingApproval = true;
                    st.pendingSkill = toolName;
                    st.finalAnswer = "写操作「" + toolName + "」需人工审批,已挂起会话。请在审批面板批准后继续。";
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "pending", "need_approval", false, riskName, latency));
                    break;
                }
                if (oc == ApprovalGate.Outcome.REJECTED) {
                    AiToolResult r = AiToolResult.fail("forbidden", "写操作被审批拒绝");
                    appendTrace(st, toolName, callJson, r);
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "error", "forbidden", false, riskName, latency));
                    continue;
                }
                // APPROVED → fail-closed 写前审计 + 回读校验(未落库拒执行)
                Long auditId = auditService.recordReturning(ctx == null ? null : ctx.getUserName(), "AI_AGENT_WRITE", "POST",
                        "/api/ai/agent/tool/" + toolName, ctx == null ? null : ctx.getIp(), null, "args=" + argsToStr(argsNode));
                if (!auditService.existsById(auditId)) {
                    AiToolResult r = AiToolResult.fail("audit_failed", "写前审计未落库, 拒绝执行");
                    appendTrace(st, toolName, callJson, r);
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "error", "audit_failed", false, riskName, latency));
                    continue;
                }
            }

            AiToolResult result;
            long e0 = System.currentTimeMillis();
            try {
                result = tool.invoke(argsNode, ctx);
            } catch (Exception ex) {
                result = AiToolResult.fail("exec_failed", ex.getMessage());
            }
            long execLatency = System.currentTimeMillis() - e0;

            // 写工具: 补一条结果审计(发起+结果 双流水)
            if (!tool.readOnly()) {
                auditService.record(ctx == null ? null : ctx.getUserName(), "AI_AGENT_WRITE_RESULT", "POST",
                        "/api/ai/agent/tool/" + toolName, ctx == null ? null : ctx.getIp(),
                        result.isOk() ? 200 : 500, cap(toJson(result), 2000));
            }

            appendTrace(st, toolName, callJson, result);
            String resultData = toJson(result);
            newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                    cap(resultData, STORE_RESULT_CAP),
                    result.getStatus(), result.getType(), tool.readOnly(), tool.risk().name(), execLatency));
        }

        // 达步数上限仍无终答 → 一次 grace call 收尾
        if (!st.done && !st.blocked && !st.awaitingApproval) {
            String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText);
            String raw = aiAssistService.chat(
                    "已达步数上限，请基于以上已获取的信息直接给出最终中文答复，不要再调用工具。", context);
            st.finalAnswer = AgentTextUtil.sanitize(raw);
            st.done = true;
            newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, null, null, null, null, "ok", null, true, "LOW", null));
        }

        // 落库会话终态
        session.setStatus(st.awaitingApproval ? "wait_approval" : (st.blocked ? "blocked" : "done"));
        session.setBudgetStepsUsed(st.seq);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        // 自学习: 成功完成且有实质工具调用时, 异步蒸馏经验入库+向量化(失败静默, 不影响本轮返回)
        if (st.done && !st.blocked && !st.awaitingApproval && hasToolCall(newSteps)) {
            try {
                aiMemoryService.learn(session.getSessionId(), session.getGoalIntent(),
                        ctx == null ? null : ctx.getUserName(), st.trace.toString(), st.finalAnswer);
            } catch (Exception e) {
                log.warn("[memory] 触发 learn 失败 session={}: {}", session.getSessionId(), e.getMessage());
            }
        }

        resp.put("sessionId", session.getSessionId());
        resp.put("status", st.awaitingApproval ? "wait_approval" : (st.blocked ? "blocked" : "done"));
        resp.put("finalAnswer", st.finalAnswer != null ? st.finalAnswer
                : (st.blockReason != null ? st.blockReason : "（无答复）"));
        resp.put("steps", stepsToDto(newSteps));
        return resp;
    }

    // ============ 会话/步骤 ============

    private DnAiSession loadOrInit(String sessionId, String userMessage, AgentContext ctx) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            DnAiSession exist = sessionMapper.selectOne(
                    new QueryWrapper<DnAiSession>().eq("session_id", sessionId.trim()).last("LIMIT 1"));
            if (exist != null) {
                exist.setStatus("running");
                exist.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(exist);
                return exist;
            }
        }
        DnAiSession s = new DnAiSession();
        s.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        s.setUserName(ctx != null ? ctx.getUserName() : null);
        s.setGoalIntent(cap(userMessage, 2000));
        s.setStatus("running");
        s.setInterruptFlag(0);
        s.setBudgetStepsUsed(0);
        s.setVersion(0);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    private int nextSeq(String sessionId) {
        Long cnt = stepMapper.selectCount(new QueryWrapper<DnAiStep>().eq("session_id", sessionId));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** seed 既往会话的 FINAL 摘要进 trace（多轮记忆，最多近 3 条）。 */
    private void seedHistory(AgentState st) {
        try {
            List<DnAiStep> prior = stepMapper.selectList(new QueryWrapper<DnAiStep>()
                    .eq("session_id", st.session.getSessionId())
                    .eq("step_type", "FINAL")
                    .orderByDesc("seq").last("LIMIT 3"));
            if (prior == null || prior.isEmpty()) return;
            StringBuilder h = new StringBuilder("（历史对话摘要）\n");
            for (int i = prior.size() - 1; i >= 0; i--) {
                DnAiStep s = prior.get(i);
                if (s != null && s.getContent() != null) {
                    h.append("- 先前答复: ").append(cap(s.getContent(), 300)).append('\n');
                }
            }
            st.trace.append(h).append('\n');
        } catch (Exception e) {
            log.warn("seed 历史失败 session={}: {}", st.session.getSessionId(), e.getMessage());
        }
    }

    private void appendTrace(AgentState st, String toolName, String callJson, AiToolResult result) {
        st.trace.append("步骤").append(st.seq).append(" 调用工具 ").append(toolName)
                .append(" 参数").append(cap(callJson, 400))
                .append(" → ").append(result.getStatus());
        if (result.isOk()) {
            st.trace.append(": ").append(cap(toJson(result.getData()), TRACE_RESULT_CAP));
        } else {
            st.trace.append("(").append(result.getType()).append("): ").append(cap(result.getMessage(), 400));
        }
        st.trace.append('\n');
    }

    private DnAiStep writeStep(AgentState st, String stepType, String role, String content, String think,
                               String skillName, String argsJson, String resultData,
                               String resultStatus, String resultType, boolean readOnly, String risk, Long latency) {
        DnAiStep step = new DnAiStep();
        step.setSessionId(st.session.getSessionId());
        step.setSeq(st.seq++);
        step.setStepType(stepType);
        step.setRole(role);
        step.setContent(content == null ? null : AgentTextUtil.sanitize(content));
        step.setThinkContent(think == null ? null : AgentTextUtil.sanitize(think));
        step.setSkillName(skillName);
        AiTool t = skillName == null ? null : toolRegistry.find(skillName);
        step.setSkillGroup(t != null ? t.group() : null);
        step.setArgsJson(argsJson);
        step.setResultStatus(resultStatus);
        step.setResultType(resultType);
        step.setResultData(resultData == null ? null : AgentTextUtil.sanitize(resultData));
        step.setReadOnly(readOnly ? 1 : 0);
        step.setRiskLevel(risk);
        step.setLatencyMs(latency);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        return step;
    }

    private List<Map<String, Object>> stepsToDto(List<DnAiStep> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DnAiStep s : steps) {
            if (s == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("stepType", s.getStepType());
            m.put("role", s.getRole());
            m.put("content", s.getContent());
            m.put("skillName", s.getSkillName());
            m.put("skillGroup", s.getSkillGroup());
            m.put("argsJson", s.getArgsJson());
            m.put("resultStatus", s.getResultStatus());
            m.put("resultType", s.getResultType());
            m.put("resultData", s.getResultData());
            m.put("readOnly", s.getReadOnly());
            m.put("riskLevel", s.getRiskLevel());
            m.put("latencyMs", s.getLatencyMs());
            out.add(m);
        }
        return out;
    }

    /** 把业务情境 bizCtx 渲染为中文一段(供 prompt 注入)，缺省返回 null。 */
    private String buildBizCtxText(Map<String, Object> bizCtx) {
        if (bizCtx == null || bizCtx.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        Object route = bizCtx.get("route");
        if (route != null) sb.append("用户当前在『").append(routeLabel(String.valueOf(route))).append("』模块。");
        Object db = bizCtx.get("db"), table = bizCtx.get("table");
        if (db != null && table != null) sb.append("正在查看数据表 ").append(db).append('.').append(table).append('。');
        else if (table != null) sb.append("正在查看表 ").append(table).append('。');
        Object ruleId = bizCtx.get("ruleId");
        if (ruleId != null) sb.append("关注质量规则 ruleId=").append(ruleId).append('。');
        Object jobId = bizCtx.get("jobId"), jobName = bizCtx.get("jobName");
        if (jobId != null) sb.append("关注同步任务 #").append(jobId).append(jobName != null ? ("(" + jobName + ")") : "").append('。');
        Object runId = bizCtx.get("runId"), taskName = bizCtx.get("taskName");
        if (runId != null) sb.append("关注调度运行 run#").append(runId).append(taskName != null ? ("(" + taskName + ")") : "").append('。');
        Object metricId = bizCtx.get("metricId");
        if (metricId != null) sb.append("关注指标 metricId=").append(metricId).append('。');
        Object taskId = bizCtx.get("taskId"), taskType = bizCtx.get("taskType");
        if (taskId != null) sb.append("关注调度任务 taskId=").append(taskId).append(taskType != null ? ("(类型" + taskType + ")") : "").append('。');
        Object projectId = bizCtx.get("projectId");
        if (projectId != null) sb.append("关注项目 projectId=").append(projectId).append('。');
        sb.append("回答时优先围绕该情境；如涉及具体表/规则/任务，在结论中用 [表:库.表] / [规则:#id] / [任务:#id] 标记以便用户一键跳转。");
        String s = sb.toString().trim();
        return s.isEmpty() ? null : AgentTextUtil.sanitize(s);
    }

    /** RAG: 向量召回与目标相关的资产, 渲染为线索文本注入首轮 prompt; 仅向量引擎命中才注入(关键字噪音大跳过), 异常返 null 降级。 */
    @SuppressWarnings("unchecked")
    private String buildRagText(String query) {
        try {
            Map<String, Object> rag = semanticSearchService.search(query, null, RAG_TOPK);
            if (rag == null || !"vector".equals(rag.get("engine"))) return null;
            Object resObj = rag.get("results");
            if (!(resObj instanceof List)) return null;
            List<Map<String, Object>> results = (List<Map<String, Object>>) resObj;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> r : results) {
                if (r == null) continue;
                Object name = r.get("name");
                if (name == null) continue;
                Object db = r.get("db"), title = r.get("title"), score = r.get("score");
                sb.append("- ").append(r.get("kind") == null ? "表" : r.get("kind")).append(" ")
                        .append(db == null ? "" : db + ".").append(name);
                if (title != null && !String.valueOf(title).trim().isEmpty()) sb.append(" — ").append(title);
                if (score != null) {
                    try { sb.append(" (相关度 ").append(String.format("%.2f", Double.parseDouble(String.valueOf(score)))).append(")"); }
                    catch (Exception ignore) {}
                }
                sb.append('\n');
                if (sb.length() >= RAG_TEXT_CAP) break;
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : AgentTextUtil.sanitize(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String routeLabel(String route) {
        switch (route == null ? "" : route) {
            case "catalog": return "数据地图";
            case "governance": return "数据治理";
            case "dbsync": return "数据同步";
            case "operations": return "数据运维";
            case "develop": return "数据开发";
            case "metrics": return "指标管理";
            case "mdm": return "主数据";
            case "project": return "项目管理";
            case "quality": return "数据质量";
            case "home": return "首页";
            default: return route;
        }
    }

    /** 本轮是否有实质工具调用(决定是否值得沉淀经验)。 */
    private boolean hasToolCall(List<DnAiStep> steps) {
        if (steps == null) return false;
        for (DnAiStep s : steps) {
            if (s != null && "SKILL_CALL".equals(s.getStepType()) && s.getSkillName() != null) return true;
        }
        return false;
    }

    private String argsToStr(JsonNode args) {
        return args == null ? null : cap(args.toString(), 2000);
    }

    private String toJson(Object o) {
        if (o == null) return "";
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }
}
