package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.model.DnAiSession;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * todo：任务计划清单工具(自主规划透明化, 天工开物·逐道工序透明)。
 * 维护本会话 dn_ai_session.plan_json 的有序步骤+状态, 让多步任务进度可见可控。
 * 元工具: 只管理 agent 自身计划, 不碰任何业务数据 → readOnly 视角, 不触护栏/审批。
 */
@Component
@RequiredArgsConstructor
public class TodoTool implements AiTool {

    private final DnAiSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "todo"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "管理任务计划清单(把多步任务拆解为有序步骤并跟踪进度, 进度透明可复核)。"
                + "action=set: 写整份计划, steps 为数组如 [{\"step\":\"查现状\",\"status\":\"pending\"},…]; "
                + "action=update: 改某步状态, index(从0起)+status(pending/doing/done); "
                + "action=get: 读当前计划。面对多步任务请先 set 规划, 每完成一步再 update。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"action\":{\"type\":\"string\",\"required\":true,\"desc\":\"set/update/get\"},"
                + "\"steps\":{\"type\":\"array\",\"required\":false,\"desc\":\"set时: [{step,status}]\"},"
                + "\"index\":{\"type\":\"number\",\"required\":false,\"desc\":\"update时: 步骤下标(0起)\"},"
                + "\"status\":{\"type\":\"string\",\"required\":false,\"desc\":\"update时: pending/doing/done\"}}";
    }
    @Override public boolean readOnly() { return true; } // 元工具: 仅管 agent 自身计划, 不写业务数据
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return AiToolResult.fail("bad_arguments", "缺少会话上下文, 无法维护计划");
        }
        String action = AgentArgs.str(args, "action");
        if (action == null) return AiToolResult.fail("bad_arguments", "action 不能为空(set/update/get)");

        DnAiSession session = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (session == null) return AiToolResult.fail("not_found", "会话不存在");

        ArrayNode plan = parsePlan(session.getPlanJson());

        switch (action) {
            case "set": {
                JsonNode steps = args == null ? null : args.get("steps");
                if (steps == null || !steps.isArray() || steps.size() == 0) {
                    return AiToolResult.fail("bad_arguments", "set 需提供非空 steps 数组");
                }
                ArrayNode np = objectMapper.createArrayNode();
                for (JsonNode s : steps) {
                    String stepText = s.isTextual() ? s.asText() : s.path("step").asText(null);
                    if (stepText == null || stepText.trim().isEmpty()) continue;
                    String status = s.path("status").asText("pending");
                    np.add(stepNode(stepText.trim(), normStatus(status)));
                }
                if (np.size() == 0) return AiToolResult.fail("bad_arguments", "steps 无有效步骤");
                savePlan(sessionId, np);
                return AiToolResult.ok(planResult(np, "已规划 " + np.size() + " 个步骤"));
            }
            case "update": {
                if (plan.size() == 0) return AiToolResult.fail("no_plan", "尚无计划, 请先 set");
                int idx = AgentArgs.intVal(args, "index", -1);
                if (idx < 0 || idx >= plan.size()) {
                    return AiToolResult.fail("bad_arguments", "index 越界(应 0~" + (plan.size() - 1) + ")");
                }
                String status = AgentArgs.str(args, "status");
                if (status == null) return AiToolResult.fail("bad_arguments", "update 需提供 status");
                ((ObjectNode) plan.get(idx)).put("status", normStatus(status));
                savePlan(sessionId, plan);
                return AiToolResult.ok(planResult(plan, "步骤" + idx + " → " + normStatus(status)));
            }
            case "get":
                return AiToolResult.ok(planResult(plan, plan.size() == 0 ? "暂无计划" : "当前 " + plan.size() + " 步"));
            default:
                return AiToolResult.fail("bad_arguments", "未知 action: " + action + "(应为 set/update/get)");
        }
    }

    private ArrayNode parsePlan(String planJson) {
        if (planJson == null || planJson.trim().isEmpty()) return objectMapper.createArrayNode();
        try {
            JsonNode n = objectMapper.readTree(planJson);
            if (n.isArray()) return (ArrayNode) n;
            JsonNode steps = n.get("steps");
            if (steps != null && steps.isArray()) return (ArrayNode) steps;
        } catch (Exception ignore) {}
        return objectMapper.createArrayNode();
    }

    private void savePlan(String sessionId, ArrayNode plan) {
        ObjectNode wrap = objectMapper.createObjectNode();
        wrap.set("steps", plan);
        sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId)
                .set("plan_json", wrap.toString())
                .set("updated_at", LocalDateTime.now()));
    }

    private ObjectNode stepNode(String step, String status) {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("step", step);
        o.put("status", status);
        return o;
    }

    private ObjectNode planResult(ArrayNode plan, String note) {
        ObjectNode r = objectMapper.createObjectNode();
        r.put("note", note);
        int done = 0;
        for (JsonNode s : plan) if ("done".equals(s.path("status").asText())) done++;
        r.put("progress", done + "/" + plan.size());
        r.set("steps", plan);
        return r;
    }

    private static String normStatus(String s) {
        if (s == null) return "pending";
        String v = s.trim().toLowerCase();
        if (v.equals("doing") || v.equals("in_progress") || v.equals("进行中")) return "doing";
        if (v.equals("done") || v.equals("completed") || v.equals("完成") || v.equals("已完成")) return "done";
        return "pending";
    }
}
