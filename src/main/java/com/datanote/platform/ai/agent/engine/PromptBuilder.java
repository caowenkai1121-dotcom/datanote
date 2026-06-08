package com.datanote.platform.ai.agent.engine;

import org.springframework.stereotype.Component;

/**
 * 三层 prompt 拼装：stable(身份+工具纪律+工具清单+Hermes协议) / context(目标+日期) / volatile(已执行步骤与结果)。
 * 整段作为 chat(userMessage, context) 的 context 传入。
 */
@Component
public class PromptBuilder {

    private static final String IDENTITY =
            "# 身份\n" +
            "你是 DataNote 数据平台的『天工·自由意志数据智能体（天工司辰）』。\n" +
            "秉承《天工开物》之道：善用工具链编排、逐道工序透明可复核、务实最小够用；\n" +
            "秉承自由意志：在护栏内自主规划、自主选择路径、目标驱动。\n";

    private static final String PROTOCOL =
            "# 工具调用协议（严格遵守）\n" +
            "1. 你只能调用上面【可用工具】清单中列出的工具，不得臆造工具名。\n" +
            "2. 当你需要更多信息时，只输出**一个**工具调用，格式严格为：\n" +
            "   <tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>\n" +
            "   不要输出多个 tool_call，不要在 tool_call 之外附加多余解释。\n" +
            "3. 工具结果会以『工具结果』形式追加到上下文中；你据此决定下一步。\n" +
            "4. 当你已掌握足够信息能回答用户时，**不要**再输出 tool_call，直接用中文给出最终答复（结论+关键数据+建议）。\n" +
            "5. 工具返回 error 时，依据 type/message 调整参数重试，或如实说明并给出力所能及的答复。\n" +
            "6. 当前所有工具均为只读探查，无写副作用，可放心组合调用。\n";

    /**
     * @param goal             本次/本会话目标
     * @param toolsManifestJson 机读工具清单
     * @param traceText        已执行步骤与工具结果摘要（volatile）
     * @param today            当前日期(到天)
     */
    public String build(String goal, String toolsManifestJson, String traceText, String today) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(IDENTITY).append('\n');
        sb.append("# 可用工具（机读清单）\n").append(toolsManifestJson == null ? "[]" : toolsManifestJson).append("\n\n");
        sb.append(PROTOCOL).append('\n');
        if (goal != null && !goal.trim().isEmpty()) {
            sb.append("# 本次目标\n").append(goal.trim()).append("\n\n");
        }
        sb.append("# 当前日期\n").append(today == null ? "" : today).append("\n\n");
        if (traceText != null && !traceText.trim().isEmpty()) {
            sb.append("# 已执行步骤与工具结果\n").append(traceText.trim()).append('\n');
        }
        return sb.toString();
    }
}
