package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
import com.datanote.platform.ai.agent.model.DnAiStep;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * read_tool_result：按 seq 取回之前某一步工具的【完整结果】(超大工具结果落盘的取数侧, 天工开物·务实token经济)。
 * trace 中过大的结果会被折叠为预览并提示"需全量可 read_tool_result(seq=N)"; 本工具从 dn_ai_step.result_data 取全量。
 * 只读元工具, 仅读本会话自身步骤, 不碰业务数据。
 */
@Component
@RequiredArgsConstructor
public class ReadToolResultTool implements AiTool {

    private final DnAiStepMapper stepMapper;
    // 注: 全量数据已在 dn_ai_step.result_data(至 STORE_RESULT_CAP), 此处直接返回该字段全文

    @Override public String name() { return "read_tool_result"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "取回之前某一步工具调用的完整结果(当上文把结果折叠成预览、提示可 read_tool_result 时使用)。参数 seq=步骤序号。仅限本会话。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"seq\":{\"type\":\"number\",\"required\":true,\"desc\":\"要取全量结果的步骤序号\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return AiToolResult.fail("bad_arguments", "缺少会话上下文");
        }
        int seq = AgentArgs.intVal(args, "seq", -1);
        if (seq < 0) return AiToolResult.fail("bad_arguments", "seq 必须为非负整数");
        DnAiStep step = stepMapper.selectOne(new QueryWrapper<DnAiStep>()
                .eq("session_id", sessionId).eq("seq", seq).last("LIMIT 1"));
        if (step == null) return AiToolResult.fail("not_found", "未找到步骤 seq=" + seq);
        if (step.getResultData() == null || step.getResultData().isEmpty()) {
            return AiToolResult.fail("not_found", "步骤 seq=" + seq + " 无可取的结果数据(可能非工具步或结果为空)");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("skillName", step.getSkillName());
        data.put("resultStatus", step.getResultStatus());
        data.put("resultData", step.getResultData());
        return AiToolResult.ok(data);
    }
}
