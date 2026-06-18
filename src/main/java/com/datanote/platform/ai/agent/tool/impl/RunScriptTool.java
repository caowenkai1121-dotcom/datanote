package com.datanote.platform.ai.agent.tool.impl;

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
 * 写工具(HIGH): 运行【数据开发】的 SQL 脚本(DnScript, 如 DWD/DWS/ADS 加工脚本), 在 Doris 执行其内容产出目标表。
 * 补齐 agent "能 create_script 建脚本却不能运行"的缺口(同 run_ods_task 对 ODS 任务)。
 * 复用 TaskExecutionService.runScriptManually(与调度同款 executeScript 核心), 零重复。
 */
@Component
@RequiredArgsConstructor
public class RunScriptTool implements AiTool {

    private final com.datanote.domain.orchestration.TaskExecutionService taskExecutionService;
    private final com.datanote.domain.develop.mapper.DnScriptMapper scriptMapper;

    @Override public String name() { return "run_script"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "【运行『数据开发』里的 SQL 脚本(DnScript, 如 DWD/DWS/ADS 加工脚本)】(写操作, 高风险, 需审批): "
                + "按 scriptId 执行脚本内容(在 Doris 跑 SQL, 自动替换 ${bizdate}=昨天), 产出目标表。"
                + "用户说『运行/执行这个脚本/跑 DWD(DWS/ADS)加工』时, 对 create_script 建好的脚本用本工具(传 scriptId)。"
                + "参数 scriptId 必填(create_script 返回的脚本ID)。完成返回成功/失败与日志。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"scriptId\":{\"type\":\"number\",\"required\":true,\"desc\":\"脚本ID(create_script 返回)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "develop:run"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long scriptId = AgentArgs.longVal(args, "scriptId");
            if (scriptId == null) return AiToolResult.fail("bad_arguments", "scriptId 不能为空");
            com.datanote.domain.develop.model.DnScript script = scriptMapper.selectById(scriptId);
            if (script == null) {
                return AiToolResult.fail("not_found", "脚本不存在: " + scriptId + "(请确认是 create_script 返回的脚本ID)");
            }
            String user = ctx != null ? ctx.getUserName() : null;
            com.datanote.domain.orchestration.model.DnTaskExecution exec = taskExecutionService.runScriptManually(scriptId, user);
            boolean ok = "SUCCESS".equals(exec.getStatus());
            if (!ok) {
                String lg = exec.getLog();
                String tail = lg != null && lg.length() > 800 ? lg.substring(lg.length() - 800) : (lg == null ? "" : lg);
                return AiToolResult.fail("script_failed", "脚本 " + scriptId + "(" + script.getScriptName() + ") 执行失败。日志末段: " + tail);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scriptId", scriptId);
            out.put("scriptName", script.getScriptName());
            out.put("scriptType", script.getScriptType());
            out.put("status", exec.getStatus());
            out.put("durationSec", exec.getDuration());
            out.put("success", true);
            out.put("location", "数据开发");
            out.put("note", "脚本 " + script.getScriptName() + " 已执行成功; 请按需用 asset_detail/table_profile 核实目标表产出");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
