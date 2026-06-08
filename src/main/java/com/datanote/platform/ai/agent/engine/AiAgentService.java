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

    /** M1 单轮最大步数（含工具调用），防失控 */
    private static final int MAX_STEPS = 6;
    /** trace 中单条结果摘要上限，控制 token */
    private static final int TRACE_RESULT_CAP = 1500;
    /** 入库 result_data 上限 */
    private static final int STORE_RESULT_CAP = 8000;

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
        boolean first = true;

        for (int i = 0; i < MAX_STEPS && !st.done && !st.blocked; i++) {
            String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today);
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

            AiToolResult result;
            long e0 = System.currentTimeMillis();
            try {
                result = tool.invoke(argsNode, ctx);
            } catch (Exception ex) {
                result = AiToolResult.fail("exec_failed", ex.getMessage());
            }
            long execLatency = System.currentTimeMillis() - e0;

            appendTrace(st, toolName, callJson, result);
            String resultData = toJson(result);
            newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                    cap(resultData, STORE_RESULT_CAP),
                    result.getStatus(), result.getType(), tool.readOnly(), tool.risk().name(), execLatency));
        }

        // 达步数上限仍无终答 → 一次 grace call 收尾
        if (!st.done && !st.blocked) {
            String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today);
            String raw = aiAssistService.chat(
                    "已达步数上限，请基于以上已获取的信息直接给出最终中文答复，不要再调用工具。", context);
            st.finalAnswer = AgentTextUtil.sanitize(raw);
            st.done = true;
            newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, null, null, null, null, "ok", null, true, "LOW", null));
        }

        // 落库会话终态
        session.setStatus(st.blocked ? "blocked" : "done");
        session.setBudgetStepsUsed(st.seq);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        resp.put("sessionId", session.getSessionId());
        resp.put("status", st.blocked ? "blocked" : "done");
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
