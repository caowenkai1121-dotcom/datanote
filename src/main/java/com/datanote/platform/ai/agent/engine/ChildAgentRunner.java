package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
import com.datanote.platform.ai.agent.model.DnAiSession;
import com.datanote.platform.ai.agent.model.DnAiStep;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolRegistry;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 子代理执行器(借鉴 hermes 委派并行/隔离子 agent)。
 * 每个子任务在【隔离会话】中跑一个【只读受限】的子 agent(独立 IterationBudget), 只回 summary 给父。
 *
 * 安全铁律(自由意志·护栏内自主):
 *  - 子工具集 = 父只读工具 减去 {delegate_task, ask_user, todo}: 子不能写业务数据、不能再委派(防递归, 深度=1)、不能反向求助/规划。
 *  - 子写操作 fail-closed: 任何非只读工具一律拒绝(子无权), 危险动作仍须父层走审批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChildAgentRunner {

    private final AiAssistService aiAssistService;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final DnAiSessionMapper sessionMapper;
    private final DnAiStepMapper stepMapper;

    // @Lazy 打破循环: AiToolRegistry 收集所有 AiTool(含 delegate_task→ChildAgentRunner), 此处惰性注入避免环
    @Lazy
    @Autowired
    private AiToolRegistry toolRegistry;

    @Resource(name = "aiChildExecutor")
    private ThreadPoolTaskExecutor childExecutor;

    // 子代理默认模型档位: 子做的是机械取数(table_profile/asset_detail 列字段), 快速档足够且大幅提速;
    // 父代理(默认档/用户选档)负责综合汇总。父若已选档(⚡/🎯)则优先继承父档。空=不覆盖用全局默认。
    @org.springframework.beans.factory.annotation.Value("${datanote.ai.child-model:deepseek-v4-flash}")
    private String childModelDefault;

    private static final int MAX_CHILD_STEPS = 5;
    private static final int CHILD_RESULT_CAP = 3000; // 放宽: 子代理工具结果(字段/表清单)不被切, 父汇总不丢明细
    private static final long CHILD_TIMEOUT_SEC = 180;
    /** 子代理禁用工具: 写须父审批, 委派防递归, 求助/规划/排程是父职责(cron_job 纳入防 cron 模式子绕过自排程) */
    private static final Set<String> CHILD_BLOCKED = new HashSet<>(Arrays.asList("delegate_task", "ask_user", "todo", "cron_job"));

    public static class ChildResult {
        public String goal;
        public String childSessionId;
        public String summary;
        public String status;
        public ChildResult(String goal, String sid, String summary, String status) {
            this.goal = goal; this.childSessionId = sid; this.summary = summary; this.status = status;
        }
    }

    /** 批量并行跑子任务(≤4 并发, 各子独立超时); 结果按提交序返回。 */
    public List<ChildResult> runBatch(List<String> goals, AgentContext parentCtx, String parentSessionId) {
        // 父线程模型档位(如用户选⚡快速): 子代理在新线程不继承 ThreadLocal, 此处显式捕获下传, 让快速档对子也生效;
        // 父未选档时, 子默认走快速档(childModelDefault)提速, 综合质量由父代理保证
        String pm = AiAssistService.getModelOverride();
        final String parentModel = (pm != null && !pm.trim().isEmpty()) ? pm
                : (childModelDefault != null && !childModelDefault.trim().isEmpty() ? childModelDefault.trim() : null);
        List<CompletableFuture<ChildResult>> fs = new ArrayList<>();
        for (String g : goals) {
            final String goal = g;
            try {
                fs.add(CompletableFuture.supplyAsync(() -> runChild(goal, parentCtx, parentSessionId, parentModel), childExecutor));
            } catch (java.util.concurrent.RejectedExecutionException rex) { // 池满: 降级占位(不压父线程), 保结果按序对应
                fs.add(CompletableFuture.completedFuture(new ChildResult(goal, null, "子任务执行池繁忙, 已跳过(稍后重试或减少并行)", "error")));
            }
        }
        List<ChildResult> out = new ArrayList<>();
        for (int i = 0; i < fs.size(); i++) {
            try {
                out.add(fs.get(i).get(CHILD_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (Exception e) {
                fs.get(i).cancel(true); // 超时取消, 触发子循环墙钟自检收手
                out.add(new ChildResult(goals.get(i), null, "子任务执行失败/超时: " + e.getMessage(), "error"));
            }
        }
        return out;
    }

    /** 单子任务: 隔离会话 + 只读工具 + 独立预算; 返回 summary。 */
    public ChildResult runChild(String goal, AgentContext parentCtx, String parentSessionId) {
        return runChild(goal, parentCtx, parentSessionId, null);
    }

    /** 同上, parentModel 为父线程模型档位(子线程显式继承, 让⚡快速对子代理生效); null=用默认。 */
    public ChildResult runChild(String goal, AgentContext parentCtx, String parentSessionId, String parentModel) {
        if (goal == null || goal.trim().isEmpty()) return new ChildResult(goal, null, "(空子任务)", "error");
        if (parentModel != null) AiAssistService.setModelOverride(parentModel);
        try {
            return doRunChild(goal, parentCtx, parentSessionId);
        } finally {
            if (parentModel != null) AiAssistService.clearModelOverride();
        }
    }

    private ChildResult doRunChild(String goal, AgentContext parentCtx, String parentSessionId) {
        LocalDateTime now = LocalDateTime.now();
        DnAiSession child = new DnAiSession();
        child.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        child.setUserName(parentCtx == null ? null : parentCtx.getUserName());
        child.setGoalIntent(cap("[子任务·父" + safe(parentSessionId) + "] " + goal, 2000));
        child.setStatus("running");
        child.setInterruptFlag(0);
        child.setBudgetStepsUsed(0);
        child.setVersion(0);
        child.setCreatedAt(now);
        child.setUpdatedAt(now);
        sessionMapper.insert(child);

        AgentContext childCtx = new AgentContext(
                parentCtx == null ? null : parentCtx.getUserName(),
                parentCtx == null ? null : parentCtx.getIp(),
                null, child.getSessionId(), null);
        IterationBudget budget = new IterationBudget(MAX_CHILD_STEPS);
        String manifest = toolRegistry.toToolsManifestJson(t -> t.readOnly() && !CHILD_BLOCKED.contains(t.name()));
        String today = LocalDate.now().toString();
        StringBuilder trace = new StringBuilder();
        int[] seq = {0};
        writeChildStep(child.getSessionId(), seq, "USER", "user", goal, null, now);

        String finalAnswer = null;
        boolean first = true;
        int iter = 0, hard = MAX_CHILD_STEPS * 3;
        long deadline = System.currentTimeMillis() + CHILD_TIMEOUT_SEC * 1000L; // 墙钟封顶, 防父放弃后子成僵尸
        while (budget.remaining() > 0 && iter < hard && finalAnswer == null
                && System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            iter++;
            budget.consume();
            String ctx = promptBuilder.build(goal, manifest, trace.toString(), today, null, null, null);
            String userPrompt = first ? goal
                    : "请据上面工具结果继续：仍需信息就只输出一个 <tool_call>，否则直接给出该子任务的最终中文小结（不再输出 tool_call）。"
                    + "小结须【保留关键明细】(表名/字段名/类型/数值等), 不要过度概括, 父代理要据此汇总。";
            first = false;
            String raw = aiAssistService.chat(userPrompt, ctx);
            if (isErr(raw)) { budget.refund(); break; }
            List<String> tcs = AgentTextUtil.parseToolCalls(raw);
            if (tcs.isEmpty()) { finalAnswer = AgentTextUtil.sanitize(AgentTextUtil.stripThink(raw)); break; }
            JsonNode call;
            try { call = objectMapper.readTree(tcs.get(0)); }
            catch (Exception e) { budget.refund(); continue; }
            String name = call.path("name").asText(null);
            JsonNode args = call.get("arguments");
            AiTool tool = toolRegistry.find(name);
            // fail-closed: 仅放行只读且非禁用工具
            if (tool == null || !tool.readOnly() || CHILD_BLOCKED.contains(name)) {
                budget.refund();
                trace.append("（子代理仅限只读探查, 拒绝工具 ").append(name).append("）\n");
                writeChildStep(child.getSessionId(), seq, "SKILL_CALL", "tool", null, name, now);
                continue;
            }
            String vErr = Validation.validate(args, tool.paramsSchemaJson(), objectMapper);
            if (vErr != null) { budget.refund(); trace.append("（参数错: ").append(vErr).append("）\n"); continue; }
            AiToolResult res;
            try { res = tool.invoke(args, childCtx); }
            catch (Exception ex) { res = AiToolResult.fail("exec_failed", ex.getMessage()); }
            trace.append("调用 ").append(name).append(" → ").append(res.getStatus());
            if (res.isOk()) trace.append(": ").append(cap(toJson(res.getData()), CHILD_RESULT_CAP));
            else trace.append("(").append(res.getType()).append("): ").append(cap(res.getMessage(), 300));
            trace.append('\n');
            writeChildStep(child.getSessionId(), seq, "SKILL_CALL", "tool", null, name, now);
        }
        if (finalAnswer == null) {
            String ctx = promptBuilder.build(goal, manifest, trace.toString(), today, null, null, null);
            String raw = aiAssistService.isAvailable()
                    ? aiAssistService.chat("请基于以上信息给出该子任务的最终中文小结(保留关键明细: 表名/字段名/类型/数值, 不要过度概括)，不再调用工具。", ctx) : null;
            finalAnswer = isErr(raw) ? "(子任务未得出明确结论)" : AgentTextUtil.sanitize(AgentTextUtil.stripThink(raw));
        }
        writeChildStep(child.getSessionId(), seq, "FINAL", "assistant", finalAnswer, null, now);
        try {
            child.setStatus("done");
            child.setBudgetStepsUsed(seq[0]);
            child.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(child);
        } catch (Exception ignore) {}
        log.info("[child] 子任务完成 parent={} child={} steps={}", safe(parentSessionId), child.getSessionId(), seq[0]);
        return new ChildResult(goal, child.getSessionId(), cap(finalAnswer, 3000), "done");
    }

    private void writeChildStep(String sessionId, int[] seq, String stepType, String role, String content, String skillName, LocalDateTime now) {
        try {
            DnAiStep s = new DnAiStep();
            s.setSessionId(sessionId);
            s.setSeq(seq[0]++);
            s.setStepType(stepType);
            s.setRole(role);
            s.setContent(content == null ? null : AgentTextUtil.sanitize(content));
            s.setSkillName(skillName);
            AiTool t = skillName == null ? null : toolRegistry.find(skillName);
            s.setSkillGroup(t == null ? null : t.group());
            s.setReadOnly(1);
            s.setRiskLevel("LOW");
            s.setCreatedAt(now);
            stepMapper.insert(s);
        } catch (Exception ignore) {}
    }

    private String toJson(Object o) {
        if (o == null) return "";
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private static boolean isErr(String raw) {
        return raw == null || raw.startsWith("AI 功能未配置") || raw.startsWith("AI 请求失败") || raw.equals("AI 返回格式异常");
    }

    private static String safe(String s) { return s == null ? "?" : s; }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }
}
