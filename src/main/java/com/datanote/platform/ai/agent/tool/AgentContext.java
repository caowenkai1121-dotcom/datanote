package com.datanote.platform.ai.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 工具执行上下文：当前用户/IP/角色/会话id，供工具内审计与权限判定取用。 */
@Data
@AllArgsConstructor
public class AgentContext {
    private String userName;
    private String ip;
    private String role;
    private String sessionId;
}
