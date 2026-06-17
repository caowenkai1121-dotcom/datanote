package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.DataModelService;
import com.datanote.domain.datamodel.model.DnModel;
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
 * AI 工具: 新建/更新数据模型。薄适配器 → DataModelService.saveModel。
 */
@Component
@RequiredArgsConstructor
public class DatamodelSaveModelTool implements AiTool {

    private final DataModelService dataModelService;

    @Override public String name() { return "datamodel_save_model"; }
    @Override public String group() { return "datamodel"; }
    @Override public String description() {
        return "新建/更新数据模型(modelType: BIZ业务/LOGIC逻辑/PHYS物理)。modelCode 须字母开头[A-Za-z][A-Za-z0-9_]*全局唯一。"
             + "更新时传 id。审批中(PENDING)的模型禁改。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"modelCode\":{\"type\":\"string\",\"required\":true,\"desc\":\"模型编码, 字母开头, 仅含字母/数字/下划线, 全局唯一\"},"
             + "\"modelName\":{\"type\":\"string\",\"required\":true,\"desc\":\"模型名称\"},"
             + "\"modelType\":{\"type\":\"string\",\"required\":true,\"desc\":\"BIZ(业务)/LOGIC(逻辑)/PHYS(物理)\"},"
             + "\"id\":{\"type\":\"number\",\"required\":false,\"desc\":\"更新时传模型 id\"},"
             + "\"subjectId\":{\"type\":\"number\",\"required\":false,\"desc\":\"所属主题域 id\"},"
             + "\"description\":{\"type\":\"string\",\"required\":false,\"desc\":\"模型描述\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "datamodel:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String modelCode = AgentArgs.str(args, "modelCode");
            String modelName = AgentArgs.str(args, "modelName");
            String modelType = AgentArgs.str(args, "modelType");
            if (modelCode == null) return AiToolResult.fail("bad_arguments", "modelCode 不能为空");
            if (modelName == null) return AiToolResult.fail("bad_arguments", "modelName 不能为空");
            if (modelType == null) return AiToolResult.fail("bad_arguments", "modelType 不能为空(BIZ/LOGIC/PHYS)");

            DnModel model = new DnModel();
            model.setModelCode(modelCode);
            model.setModelName(modelName);
            model.setModelType(modelType.toUpperCase());
            Long id = AgentArgs.longVal(args, "id");
            if (id != null) model.setId(id);
            Long subjectId = AgentArgs.longVal(args, "subjectId");
            if (subjectId != null) model.setSubjectId(subjectId);
            String description = AgentArgs.str(args, "description");
            if (description != null) model.setDescription(description);

            DnModel saved = dataModelService.saveModel(model);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("model", saved);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (BusinessException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
