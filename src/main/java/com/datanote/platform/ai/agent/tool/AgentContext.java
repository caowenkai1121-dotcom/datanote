package com.datanote.platform.ai.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/** 工具执行上下文：当前用户/IP/角色/会话id + 业务情境(bizCtx)，供工具内审计/权限判定与缺参回退取用。 */
@Data
@AllArgsConstructor
public class AgentContext {
    private String userName;
    private String ip;
    private String role;
    private String sessionId;
    /** 业务情境：route/db/table/jobId/ruleId... 由各模块情境入口透传，工具缺参时可回退取用。 */
    private Map<String, Object> bizCtx;
}
