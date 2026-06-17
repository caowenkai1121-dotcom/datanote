package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.DataModelService;
import com.datanote.domain.datamodel.model.DnModelChange;
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
 * AI 工具: 提交数据模型送审。薄适配器 → DataModelService.submitForApproval。
 */
@Component
@RequiredArgsConstructor
public class DatamodelSubmitForApprovalTool implements AiTool {

    private final DataModelService dataModelService;

    @Override public String name() { return "datamodel_submit_for_approval"; }
    @Override public String group() { return "datamodel"; }
    @Override public String description() {
        return "提交数据模型送审(提交前自动做规范强校验: 至少1实体/每实体≥1属性/LOGIC&PHYS需主键, 校验失败阻断)。"
             + "成功后模型转 PENDING, 返回 changeId 供审批。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"modelId\":{\"type\":\"number\",\"required\":true,\"desc\":\"模型 id\"},"
             + "\"reason\":{\"type\":\"string\",\"required\":false,\"desc\":\"送审说明\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "datamodel:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long modelId = AgentArgs.longOrCtx(args, "modelId", ctx);
            if (modelId == null) return AiToolResult.fail("bad_arguments", "modelId 不能为空");
            String reason = AgentArgs.str(args, "reason");

            DnModelChange change = dataModelService.submitForApproval(modelId, reason);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("submitted", true);
            out.put("modelId", modelId);
            out.put("change", change);
            if (change != null && change.getId() != null) out.put("changeId", change.getId());
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (BusinessException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
