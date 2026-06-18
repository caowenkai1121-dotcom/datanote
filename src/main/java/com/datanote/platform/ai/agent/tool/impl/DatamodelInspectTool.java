package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.datamodel.DataModelService;
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
 * AI 只读工具: 数据模型视察(薄适配 DataModelService)。补 agent 对模型的"看"能力:
 *  - validate: 规范强校验(送审前自检: 实体/属性/主键/命名)
 *  - ddl     : 物理模型生成 DDL 预览(不执行)
 *  - versions: 模型版本列表
 *  - standard_impact: 某数据标准/元素被哪些模型属性引用(影响分析)
 */
@Component
@RequiredArgsConstructor
public class DatamodelInspectTool implements AiTool {

    private final DataModelService dataModelService;

    @Override public String name() { return "datamodel_inspect"; }
    @Override public String group() { return "datamodel"; }
    @Override public String description() {
        return "数据模型视察(只读)。action: validate(规范校验, 送审前自检, 传 modelId) / "
                + "ddl(物理模型生成DDL预览不执行, 传 modelId) / versions(版本列表, 传 modelId) / "
                + "standard_impact(数据标准被哪些模型属性引用, 传 elementCode)。用于建模/送审前核实、预览DDL、查标准影响。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"action\":{\"type\":\"string\",\"required\":true,\"desc\":\"validate/ddl/versions/standard_impact\"},"
                + "\"modelId\":{\"type\":\"number\",\"required\":false,\"desc\":\"validate/ddl/versions 的模型id\"},"
                + "\"elementCode\":{\"type\":\"string\",\"required\":false,\"desc\":\"standard_impact 的数据标准/元素编码\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "datamodel:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String action = AgentArgs.str(args, "action");
            if (action == null) return AiToolResult.fail("bad_arguments", "action 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", action);
            switch (action) {
                case "validate": {
                    Long id = AgentArgs.longVal(args, "modelId");
                    if (id == null) return AiToolResult.fail("bad_arguments", "validate 需传 modelId");
                    out.put("result", dataModelService.validateModel(id));
                    break;
                }
                case "ddl": {
                    Long id = AgentArgs.longVal(args, "modelId");
                    if (id == null) return AiToolResult.fail("bad_arguments", "ddl 需传 modelId(物理模型)");
                    out.put("ddl", dataModelService.generateDdl(id));
                    break;
                }
                case "versions": {
                    Long id = AgentArgs.longVal(args, "modelId");
                    if (id == null) return AiToolResult.fail("bad_arguments", "versions 需传 modelId");
                    out.put("versions", dataModelService.listVersions(id));
                    break;
                }
                case "standard_impact": {
                    String code = AgentArgs.str(args, "elementCode");
                    if (code == null) return AiToolResult.fail("bad_arguments", "standard_impact 需传 elementCode");
                    out.put("impact", dataModelService.standardImpact(code));
                    break;
                }
                default:
                    return AiToolResult.fail("bad_arguments", "action 仅支持 validate/ddl/versions/standard_impact");
            }
            return AiToolResult.ok(out);
        } catch (com.datanote.common.exception.BusinessException be) {
            return AiToolResult.fail("bad_arguments", be.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
