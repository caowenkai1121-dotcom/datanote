package com.datanote.platform.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具窄接口（天工开物·工具链编排的最小单元）。
 * 每个工具是对既有 domain 服务能力的薄适配器，自描述以供注册表生成机读清单注入 prompt。
 */
public interface AiTool {

    /** 全局唯一工具名（snake_case），LLM 据此调用。 */
    String name();

    /** 工具分组（gov/quality/lineage/sync/...）。 */
    String group();

    /** 给 LLM 看的能力描述（说明用途与何时使用）。 */
    String description();

    /** 参数 JSON Schema（粗）：{"db":{"type":"string","required":false}, ...}，供 LLM 与 Validation 共用。 */
    String paramsSchemaJson();

    /** 是否只读（无写副作用）。M1 全部 true。 */
    boolean readOnly();

    /** 风险级，驱动护栏（M2 起）。 */
    RiskLevel risk();

    /** 执行：args 为模型给出的入参，ctx 为执行上下文。实现内自行 try/catch 翻译为 AiToolResult。 */
    AiToolResult invoke(JsonNode args, AgentContext ctx);
}
