package com.datanote.platform.ai.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 工具统一结果契约（仿 CLI-Anything 的结构化输出/错误，专供 LLM 自纠）。
 * status: ok | error | pending；error 时 type 为机读错误枚举。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiToolResult {

    /** ok / error / pending */
    private String status;
    private Object data;
    /** unknown_tool / bad_arguments / not_found / forbidden / exec_failed / need_approval */
    private String type;
    private String message;

    public static AiToolResult ok(Object data) {
        AiToolResult r = new AiToolResult();
        r.status = "ok";
        r.data = data;
        return r;
    }

    public static AiToolResult fail(String type, String message) {
        AiToolResult r = new AiToolResult();
        r.status = "error";
        r.type = type;
        r.message = message;
        return r;
    }

    public static AiToolResult pending(String message) {
        AiToolResult r = new AiToolResult();
        r.status = "pending";
        r.type = "need_approval";
        r.message = message;
        return r;
    }

    public boolean isOk() {
        return "ok".equals(status);
    }
}
