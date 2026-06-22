package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiApprovalMapper;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
import com.datanote.platform.ai.agent.model.DnAiApproval;
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
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 天工·自由意志数据智能体 主循环（M1：单工具/步 顺序循环，零写副作用）。
 * 感知→构造prompt→调LLM→解析tool_call→校验→执行只读工具→回灌→直到终答或达步数上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {

    private final AiAssistService aiAssistService;
    private final AiToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final DnAiSessionMapper sessionMapper;
    private final DnAiStepMapper stepMapper;
    private final com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;
    private final com.datanote.domain.orchestration.LineageEdgeService lineageEdgeService;
    private final com.datanote.platform.audit.AuditService auditService;
    private final ApprovalGate approvalGate;
    private final AiMemoryService aiMemoryService;
    private final DnAiApprovalMapper approvalMapper;
    private final ContextCompressorService contextCompressor;
    private final AiFileService aiFileService;
    private final DocIngestService docIngestService;
    private final AgentPermResolver permResolver;
    private final AgentAccessChecker accessChecker;
    private final AgentEventBus eventBus;

    /** cron 定时无人值守模式(ThreadLocal): 禁 cron_job(防递归排程)/ask_user(无人应答), 不写记忆 */
    private static final ThreadLocal<Boolean> CRON_MODE = ThreadLocal.withInitial(() -> false);
    private static final java.util.Set<String> CRON_BLOCKED =
            new java.util.HashSet<>(java.util.Arrays.asList("cron_job", "ask_user"));
    /** 渐进工具披露: 工具数超阈值时只放核心(写/agent元/核心检索)+ tool_search, 其余按需发现 */
    @org.springframework.beans.factory.annotation.Value("${datanote.ai.tool-disclosure-threshold:50}")
    private int toolDisclosureThreshold;
    private static final java.util.Set<String> CORE_READ = new java.util.HashSet<>(java.util.Arrays.asList(
            "semantic_search", "graph_impact", "graph_trace", "graph_neighbors", "gov_overview", "tool_search"));

    /** 生产步上限(成功执行工具/给出终答才计数); 借鉴 hermes IterationBudget, 可纠正废步退还不蚕食。
     *  16 步: 支撑多工序长链(如探查→建表→建脚本→运行 的分层加工), 墙钟 300s 仍为最终兜底防失控 */
    private static final int MAX_PRODUCTIVE_STEPS = 16;
    /** 总迭代硬顶(含被退还的废步), 防模型连犯格式错时失控刷屏 */
    private static final int HARD_ITER_CAP = 40;
    /** 周期性规划检查点间隔(生产步): 每 N 步暂停审视目标/剩余, 防长任务漂移(借鉴 smolagents planning_interval) */
    private static final int PLAN_INTERVAL = 6;
    /** 单轮 run 墙钟上限(ms): 防 LLM 挂起/超长把 Tomcat 工作线程长期占满拖垮 Web 服务 */
    private static final long RUN_WALLCLOCK_MS = 300_000L;
    /** trace 中单条结果上限(放大到模型量级: 表/字段清单等不折叠, agent 看全; 字段清单走 compactColumns 完全不截) */
    private static final int TRACE_RESULT_CAP = 40000;
    /** 入库 result_data 上限(LONGTEXT; read_tool_result 取全量) */
    private static final int STORE_RESULT_CAP = 200000;
    /** RAG 召回条数 */
    private static final int RAG_TOPK = 5;
    private static final int DOC_TOPK = 4; // 文档知识库 RAG 召回片段数
    /** RAG 注入文本上限(控 token) */
    private static final int RAG_TEXT_CAP = 800;
    /** 自学习记忆召回条数 */
    private static final int MEM_TOPK = 3;

    /** 单轮并行多工具(提速·减往返): 复用子代理线程池跑【独立只读】工具批 */
    @javax.annotation.Resource(name = "aiChildExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor parallelExecutor;
    /** 一轮内最多并行执行的工具数(防一次喂太多压垮数仓/池) */
    private static final int MAX_PARALLEL_TOOLS = 4;
    /** 单个并行工具墙钟(秒) */
    private static final long PARALLEL_TOOL_TIMEOUT_SEC = 60;
    /** 不参与并行批的工具(需串行/特殊处理: 求助暂停/委派子代理/排程) */
    private static final java.util.Set<String> PARALLEL_EXCLUDE =
            new java.util.HashSet<>(java.util.Arrays.asList("ask_user", "delegate_task", "cron_job"));

    /** 会话级条纹锁(64): 防同一 sessionId 并发执行致 seq 竞态/状态覆盖; 无新依赖 */
    private final java.util.concurrent.locks.ReentrantLock[] SESSION_STRIPES = initStripes(64);
    private static java.util.concurrent.locks.ReentrantLock[] initStripes(int n) {
        java.util.concurrent.locks.ReentrantLock[] a = new java.util.concurrent.locks.ReentrantLock[n];
        for (int i = 0; i < n; i++) a[i] = new java.util.concurrent.locks.ReentrantLock();
        return a;
    }
    private java.util.concurrent.locks.ReentrantLock sessionLock(String sid) {
        return SESSION_STRIPES[(sid == null ? 0 : (sid.hashCode() & 0x7fffffff) % SESSION_STRIPES.length)];
    }
    /** 会话归属校验: 调用者≠发起人则拒(越权隔离); 匿名态(鉴权关闭)放行。 */
    private boolean ownerOk(DnAiSession s, AgentContext ctx) {
        if (s == null) return true;
        String caller = ctx == null ? null : ctx.getUserName();
        String owner = s.getUserName();
        // 匿名态调用 / 超管放行; 无主或开放态(anonymous)创建的历史会话亦放行
        if (caller == null || "anonymous".equals(caller) || "admin".equals(caller)) return true;
        if (owner == null || "anonymous".equals(owner)) return true;
        return caller.equals(owner);
    }

    /** cron 定时任务入口: 无人值守模式跑一次(禁 cron_job/ask_user, 不写记忆, 写操作仍走审批挂起)。 */
    public Map<String, Object> runCron(String prompt, AgentContext ctx) {
        CRON_MODE.set(Boolean.TRUE);
        try {
            return run(null, prompt, ctx);
        } finally {
            CRON_MODE.remove();
        }
    }

    /** 一轮对话：发起 userMessage，返回 {sessionId, status, finalAnswer, steps:[...]}。 */
    public Map<String, Object> run(String sessionId, String userMessage, AgentContext ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();

        if (userMessage == null || userMessage.trim().isEmpty()) {
            resp.put("status", "error");
            resp.put("finalAnswer", "请输入你的问题。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }

        // 降级守门：AI 未配置直返提示，不进循环
        if (!aiAssistService.isAvailable()) {
            DnAiSession s = loadOrInit(sessionId, userMessage, ctx);
            resp.put("sessionId", s.getSessionId());
            resp.put("status", "blocked");
            resp.put("finalAnswer", "AI 功能未配置。请在【系统配置 → AI 配置】中设置 API Key 后再使用智能体。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }

        DnAiSession session;
        try {
            session = loadOrInit(sessionId, userMessage, ctx);
        } catch (SecurityException se) { // 越权: 访问他人会话
            resp.put("status", "blocked");
            resp.put("finalAnswer", "无权访问该会话(越权隔离)。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }
        if (ctx != null) ctx.setSessionId(session.getSessionId()); // 真实会话id回填, 让 todo 等需要 sessionId 的工具可用
        // 会话级互斥: 防同一 sessionId 并发执行(前端双击 / chat 与 answer 竞争)致 seq 竞态/状态覆盖
        java.util.concurrent.locks.ReentrantLock _lk = sessionLock(session.getSessionId());
        if (!_lk.tryLock()) {
            resp.put("sessionId", session.getSessionId());
            resp.put("status", "running");
            resp.put("finalAnswer", "该会话正在执行中, 请稍候再发送。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }
        try {
        AgentState st = new AgentState();
        st.session = session;
        st.seq = nextSeq(session.getSessionId());

        // 多轮历史：把既往 FINAL 摘要 seed 进 trace
        seedHistory(st);
        // 续跑保连贯: 若上次因 ask_user/中断挂起, 把最近 FINAL 之后的在途工具结果 seed 回 trace
        seedContinuation(st);

        List<DnAiStep> newSteps = new ArrayList<>();
        List<Map<String, Object>> previews = new ArrayList<>(); // 表数据预览(未截断, 独立通道回传前端渲染表格)
        // 记录用户消息
        newSteps.add(writeStep(st, "USER", "user", userMessage, null, null, null, null, null, null, true, "LOW", null));

        boolean cronMode = Boolean.TRUE.equals(CRON_MODE.get());
        String manifest;
        if (cronMode) {
            manifest = toolRegistry.toToolsManifestJson(t -> !CRON_BLOCKED.contains(t.name())); // cron: 隐藏 cron_job/ask_user
        } else if (toolRegistry.size() > toolDisclosureThreshold) {
            // 渐进披露: 工具多时只放 写/agent元/核心检索 + tool_search, 其余经 tool_search 发现再调用
            manifest = toolRegistry.toToolsManifestJson(t -> !t.readOnly() || "agent".equals(t.group()) || CORE_READ.contains(t.name()));
        } else {
            manifest = toolRegistry.toToolsManifestJson();
        }
        String today = LocalDate.now().toString();
        String bizCtxText = buildBizCtxText(ctx == null ? null : ctx.getBizCtx());
        String ragText = buildRagText(userMessage, ctx == null ? null : ctx.getUserName());   // 循环外算一次, 自动 grounding(资产+文档)
        String memoryText = aiMemoryService.recall(userMessage, ctx == null ? null : ctx.getUserName(), MEM_TOPK); // 自学习记忆召回(只读上下文)
        String filesText = buildFilesText(ctx == null ? null : ctx.getUserName()); // 已上传文件清单(让 agent 感知, 可 file_read)
        boolean first = true;
        IterationBudget budget = new IterationBudget(MAX_PRODUCTIVE_STEPS);
        int iter = 0;
        long runDeadline = System.currentTimeMillis() + RUN_WALLCLOCK_MS; // 墙钟封顶
        java.util.Map<String, Integer> seenCalls = new java.util.HashMap<>(); // 死循环护栏: 工具+参数签名计数
        java.util.Set<String> critiqueNudged = new java.util.HashSet<>();     // CRITIC 自校验: 每类写工具只提示核实一次, 防刷屏
        int[] consecToolFails = {0};                                          // 连续错误熔断(借鉴 Cline): 连续工具失败计数, 成功清零

        while (budget.remaining() > 0 && iter < HARD_ITER_CAP && System.currentTimeMillis() < runDeadline
                && !st.done && !st.blocked && !st.awaitingApproval && !st.awaitingInput) {
            iter++;
            // 每轮边界查会话(替代SSE的DB标志位轮询): 协作式中断 + 中途转向 + 取最新计划
            DnAiSession live = sessionMapper.selectOne(
                    new QueryWrapper<DnAiSession>().eq("session_id", session.getSessionId()).last("LIMIT 1"));
            if (live != null && Integer.valueOf(1).equals(live.getInterruptFlag())) {
                clearInterrupt(session.getSessionId());
                st.blocked = true; st.exitReason = "INTERRUPTED";
                st.finalAnswer = "已按你的请求中止本次任务，已完成步骤的结果已保留。";
                newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, null, null, null, null, "interrupted", null, true, "LOW", null));
                break;
            }
            if (live != null) {
                String steer = drainSteer(session.getSessionId(), live.getSteerText());
                if (steer != null) {
                    st.trace.append("【用户中途补充指引】").append(cap(steer, 600)).append('\n');
                    newSteps.add(writeStep(st, "STEER", "user", steer, null, null, null, null, "ok", null, true, "LOW", null));
                }
            }
            budget.consume(); // 先占一个生产步; 可纠正废步在下方 refund 退还(借鉴 hermes refund)
            contextCompressor.maybeCompress(st, userMessage); // 上下文压缩: trace 超阈值时摘要早期步骤(防长任务超窗)
            // 周期性规划检查点(借鉴 smolagents planning_interval): 每 PLAN_INTERVAL 步暂停审视目标/剩余, 防长任务漂移空转
            if (!first && budget.used() > 0 && budget.used() % PLAN_INTERVAL == 0) {
                st.trace.append("【规划检查点】已执行 ").append(budget.used())
                        .append(" 步。请暂停审视: 对照目标更新『已知事实/剩余步骤』(可 todo update), 判断是否已可收口或是否偏题, 再继续下一步。\n");
            }
            String planText = live == null ? null : renderPlan(live.getPlanJson());
            String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText, planText, filesText);
            String userPrompt = first
                    ? userMessage
                    : "请根据上面的『已执行步骤与工具结果』继续：若仍需信息就只输出一个 <tool_call>，否则直接给出最终中文答复（不要再输出 tool_call）。";
            first = false;

            long t0 = System.currentTimeMillis();
            // 流式(特性C): 首轮 LLM 调用在有 SSE 订阅者时走 chatStream 逐字推送 token(单轮问答/文档RAG 常见路径真流式);
            // 多步工具轮不流式(其输出是 tool_call JSON, 无展示价值), 仍走非流式 + step 事件实时反馈。
            String raw = (iter == 1 && eventBus.hasSubscribers(session.getSessionId()))
                    ? callLlmStreaming(userPrompt, context, session.getSessionId())
                    : callLlmWithRetry(userPrompt, context); // 抖动退避一次性重试: 单次 LLM 抖动不再直接中止全程
            // 反应式超窗恢复: 若报上下文超长, 强制压缩后重试一次(借鉴 hermes context overflow 压缩重试)
            if (isAiError(raw) && ErrorClassifier.classify(raw) == ErrorClassifier.Action.CONTEXT_OVERFLOW
                    && contextCompressor.forceCompress(st, userMessage)) {
                context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText, planText, filesText);
                raw = callLlmWithRetry(userPrompt, context);
            }
            long latency = System.currentTimeMillis() - t0;
            String think = AgentTextUtil.extractThink(raw); // 过程推理留痕(救活 think_content 死列, 借鉴 hermes scratchpad 分离)

            if (isAiError(raw)) {
                st.blocked = true; st.exitReason = "BLOCKED";
                st.blockReason = raw;
                newSteps.add(writeStep(st, "FINAL", "assistant", raw == null ? "AI 调用失败" : raw,
                        null, null, null, null, "error", "exec_failed", true, "LOW", latency));
                break;
            }

            List<String> toolJsons = AgentTextUtil.parseToolCalls(raw);
            if (toolJsons.isEmpty()) {
                // 终答(剥除 <think> 过程留痕 + <tool_call> 块, 防工具调用 JSON 泄漏) → 反思自检(多工具证据时, 把结论与证据对齐降幻觉)
                String draft = AgentTextUtil.cleanFinal(raw);
                // 空答兜底: 模型只写了 <think> 或返回空 → 再要一次面向用户的明确终答, 防前端"（无答复）"
                if (draft == null || draft.trim().isEmpty()) {
                    String reAsk = callLlmWithRetry(
                            "请直接面向用户给出最终中文答复(结论+关键信息, 有下载链接请用 [文件名](URL) 给出), 不要只写思考过程, 不要再调用工具。",
                            promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText));
                    draft = AgentTextUtil.cleanFinal(reAsk);
                    if (draft == null || draft.trim().isEmpty()) draft = "已完成。如需更多信息或具体数据/下载，请告诉我。";
                }
                st.finalAnswer = reflectIfNeeded(draft, newSteps, userMessage, manifest,
                        st.trace.toString(), today, bizCtxText, ragText, memoryText);
                st.done = true; st.exitReason = "DONE";
                newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer,
                        think, null, null, null, "ok", null, true, "LOW", latency));
                break;
            }

            // ===== 单轮并行多工具(提速·减往返): LLM 一次给多个【独立只读安全】调用时, 并行执行, 一轮拿齐 =====
            // 写步/trace/seq 全在主线程串行做(只 invoke 并行), 避免 st.seq 竞态; 任一不合格→回退单工具串行路径
            if (toolJsons.size() > 1 && !cronMode) {
                java.util.List<JsonNode> batch = new ArrayList<>();
                java.util.Set<String> batchSeen = new java.util.HashSet<>();        // 批内去重: 同一回复重复同一调用只跑一次
                int cap = Math.min(MAX_PARALLEL_TOOLS, budget.remaining() + 1);     // 不超预算: 顶部已占1步, 余量+1 即本批可纳上限
                boolean ok = true;
                for (String tj : toolJsons) {
                    if (batch.size() >= cap) break;
                    JsonNode c; try { c = objectMapper.readTree(tj); } catch (Exception e) { ok = false; break; }
                    String nm = c.path("name").asText(null);
                    AiTool t = toolRegistry.find(nm);
                    JsonNode a = c.get("arguments");
                    String sig = nm + "|" + argsToStr(a);
                    if (t == null || PARALLEL_EXCLUDE.contains(nm) || Guardrail.gate(t) != Guardrail.Gate.PASS
                            || PermGate.check(t, ctx) != PermGate.Decision.ALLOW
                            || accessChecker.dataDeny(nm, a, ctx) != null
                            || Validation.validate(a, t.paramsSchemaJson(), objectMapper) != null
                            || seenCalls.containsKey(sig) || batchSeen.contains(sig)) { ok = false; break; }
                    batchSeen.add(sig);
                    batch.add(c);
                }
                if (ok && batch.size() > 1) {
                    Long marker = writeRunningMarker(st, batch.get(0).path("name").asText(null));
                    try {
                        final AgentContext fctx = ctx;
                        java.util.List<java.util.concurrent.CompletableFuture<AiToolResult>> fs = new ArrayList<>();
                        for (JsonNode c : batch) {
                            final AiTool t = toolRegistry.find(c.path("name").asText(null));
                            final JsonNode a = c.get("arguments");
                            try {
                                fs.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                    try { return t.invoke(a, fctx); } catch (Exception ex) { return AiToolResult.fail("exec_failed", msgOf(ex)); }
                                }, parallelExecutor));
                            } catch (java.util.concurrent.RejectedExecutionException rex) {
                                fs.add(java.util.concurrent.CompletableFuture.completedFuture(AiToolResult.fail("busy", "并行池繁忙")));
                            }
                        }
                        for (int k = 1; k < batch.size(); k++) budget.consume(); // 该批占 batch.size() 生产步(已消费1, 补余; cap 已保证余量足)
                        for (int k = 0; k < batch.size(); k++) {
                            JsonNode c = batch.get(k);
                            String nm = c.path("name").asText(null);
                            JsonNode a = c.get("arguments");
                            AiToolResult res;
                            try { res = fs.get(k).get(PARALLEL_TOOL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS); }
                            catch (Exception e) { fs.get(k).cancel(true); res = AiToolResult.fail("exec_failed", msgOf(e)); }
                            seenCalls.merge(nm + "|" + argsToStr(a), 1, Integer::sum);
                            appendTrace(st, nm, c.toString(), res);
                            if (res.isOk() && res.getData() instanceof Map && (((Map<?, ?>) res.getData()).containsKey("_preview") || ((Map<?, ?>) res.getData()).containsKey("_chart"))) {
                                previews.add((Map<String, Object>) res.getData());
                            }
                            AiTool t = toolRegistry.find(nm);
                            newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, k == 0 ? think : null, nm, argsToStr(a),
                                    cap(toJson(res), STORE_RESULT_CAP), res.getStatus(), res.getType(),
                                    true, t == null || t.risk() == null ? "LOW" : t.risk().name(), null));
                        }
                    } finally {
                        if (marker != null) try { stepMapper.deleteById(marker); } catch (Exception ignore) {} // 异常也清进度标记, 防幽灵"运行中"
                    }
                    continue; // 下一轮 LLM 看到全部结果
                }
            }

            // M1：取首个工具调用执行
            String callJson = toolJsons.get(0);
            String toolName = null;
            JsonNode argsNode = null;
            try {
                JsonNode call = objectMapper.readTree(callJson);
                toolName = call.path("name").asText(null);
                argsNode = call.get("arguments");
            } catch (Exception e) {
                budget.refund(); // 格式可纠正, 不计生产步
                st.trace.append("步骤").append(st.seq).append(" 解析工具调用失败，请输出合法 JSON。\n");
                newSteps.add(writeStep(st, "SKILL_CALL", "assistant", AgentTextUtil.sanitize(raw),
                        null, null, callJson, null, "error", "bad_arguments", true, "LOW", latency));
                continue;
            }

            AiTool tool = toolRegistry.find(toolName);
            if (tool == null) {
                budget.refund(); // 选错工具可纠正, 不计生产步
                AiToolResult r = AiToolResult.fail("unknown_tool", "未知工具: " + toolName);
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "unknown_tool", true, "LOW", latency));
                continue;
            }

            String vErr = Validation.validate(argsNode, tool.paramsSchemaJson(), objectMapper);
            if (vErr != null) {
                budget.refund(); // 参数可纠正, 不计生产步(护栏拒/审批拒/审计失败则不退还, 让其计费尽早收敛)
                AiToolResult r = AiToolResult.fail("bad_arguments", vErr);
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "bad_arguments", tool.readOnly(), tool.risk() == null ? "HIGH" : tool.risk().name(), latency));
                continue;
            }

            // cron 无人值守守卫: 禁 cron_job(防递归排程)/ask_user(无人应答), 退还预算并提示自决
            if (cronMode && CRON_BLOCKED.contains(toolName)) {
                budget.refund();
                st.trace.append("（定时任务无人值守，禁用 ").append(toolName)
                        .append("，请基于已有信息自行决策或直接给出结论）\n");
                newSteps.add(writeStep(st, "NUDGE", "assistant", "cron 模式禁用 " + toolName, null, null, null, null, "ok", null, true, "LOW", latency));
                continue;
            }

            // ask_user: 拦截暂停, 把结构化问题回传前端渲染卡片, 等用户答后 /answer 续跑(人机协同)
            if ("ask_user".equals(toolName)) {
                JsonNode qs = argsNode == null ? null : argsNode.get("questions");
                if (qs == null || !qs.isArray() || qs.size() == 0) {
                    budget.refund();
                    AiToolResult r = AiToolResult.fail("bad_arguments", "questions 需为非空数组");
                    appendTrace(st, toolName, callJson, r);
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "error", "bad_arguments", true, "LOW", latency));
                    continue;
                }
                st.awaitingInput = true; st.exitReason = "AWAIT_INPUT";
                st.pendingQuestions = qs;
                st.finalAnswer = "我需要你先做几个选择，请在卡片中确认后继续。";
                newSteps.add(writeStep(st, "ASK_USER", "assistant", st.finalAnswer, null, "ask_user",
                        argsToStr(argsNode), null, "pending", "need_input", true, "LOW", latency));
                break;
            }

            // 死循环护栏 + nudge: 相同工具+参数重复调用结果不变, 拦下并提示换思路(退还预算不计步)
            String callSig = toolName + "|" + argsToStr(argsNode);
            if (seenCalls.merge(callSig, 1, Integer::sum) >= 2) {
                budget.refund();
                st.trace.append("【提示】你已用相同参数调用过 ").append(toolName)
                        .append(", 结果不会变。请换工具或参数, 或基于已有信息直接给出结论。\n");
                newSteps.add(writeStep(st, "NUDGE", "assistant",
                        "重复调用 " + toolName + " 已拦截, 提示换思路", null, null, null, null, "ok", null, true, "LOW", latency));
                continue;
            }

            // ===== 写操作护栏门(咽喉点): 只读直放 / 红线拒 / 写需审批 =====
            Guardrail.Gate gate = Guardrail.gate(tool);
            String riskName = tool.risk() == null ? "HIGH" : tool.risk().name();
            if (gate == Guardrail.Gate.DENY) {
                AiToolResult r = AiToolResult.fail("forbidden", "该操作属永久禁区, 拒绝执行");
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "forbidden", tool.readOnly(), riskName, latency));
                continue;
            }
            // ===== 功能级权限闸门: 发起人无该权限点 → 硬拒绝(不进审批, 不可绕过) =====
            if (PermGate.check(tool, ctx) == PermGate.Decision.DENY) {
                String need = tool.requiredPerm();
                AiToolResult r = AiToolResult.fail("permission_denied",
                        "当前用户无 " + need + " 权限, 无法执行 " + toolName + "(请联系管理员分配)");
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "permission_denied", tool.readOnly(), riskName, latency));
                continue;
            }
            // ===== 数据级闸门: 发起人无该资源数据权限 → 拒 =====
            String dataDeny = accessChecker.dataDeny(toolName, argsNode, ctx);
            if (dataDeny != null) {
                AiToolResult r = AiToolResult.fail("data_denied", dataDeny);
                appendTrace(st, toolName, callJson, r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                        null, "error", "data_denied", tool.readOnly(), riskName, latency));
                continue;
            }
            if (gate == Guardrail.Gate.NEED_APPROVAL) {
                boolean autoAppr = live != null && Integer.valueOf(1).equals(live.getAutoApprove()); // 本任务批量自动批准
                ApprovalGate.Outcome oc = approvalGate.check(st.session.getSessionId(), st.seq, tool, argsToStr(argsNode), Guardrail.isHigh(tool), autoAppr);
                if (oc == ApprovalGate.Outcome.PENDING) {
                    st.awaitingApproval = true; st.exitReason = "AWAIT_APPROVAL";
                    st.pendingSkill = toolName;
                    st.finalAnswer = "写操作「" + toolName + "」需人工审批,已挂起会话。\n"
                            + "· 单步审批: 点「批准并继续」逐个确认;\n"
                            + "· 多步任务想一次到位: 点「批准并自动批准后续」, 本任务剩余写操作将免逐个审批、一路执行到底。";
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "pending", "need_approval", false, riskName, latency));
                    break;
                }
                if (oc == ApprovalGate.Outcome.REJECTED) {
                    AiToolResult r = AiToolResult.fail("forbidden", "写操作被审批拒绝");
                    appendTrace(st, toolName, callJson, r);
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "error", "forbidden", false, riskName, latency));
                    continue;
                }
                // APPROVED → fail-closed 写前审计 + 回读校验(未落库拒执行)
                Long auditId = auditService.recordReturning(ctx == null ? null : ctx.getUserName(), "AI_AGENT_WRITE", "POST",
                        "/api/ai/agent/tool/" + toolName, ctx == null ? null : ctx.getIp(), null, "args=" + argsToStr(argsNode));
                if (!auditService.existsById(auditId)) {
                    AiToolResult r = AiToolResult.fail("audit_failed", "写前审计未落库, 拒绝执行");
                    appendTrace(st, toolName, callJson, r);
                    newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, toolName, argsToStr(argsNode),
                            null, "error", "audit_failed", false, riskName, latency));
                    continue;
                }
            }

            // 进度可见: 调用前插一条 RUNNING 标记(用后即删), 让前端轮询能实时显示"正在{工具}",
            // 防长耗时工具(如 delegate_task 并行子代理)执行期间界面看似"卡在正在理解"
            Long runningMarkerId = writeRunningMarker(st, toolName);

            AiToolResult result;
            long e0 = System.currentTimeMillis();
            try {
                result = tool.invoke(argsNode, ctx);
            } catch (Exception ex) {
                log.warn("工具 {} 执行异常", toolName, ex);
                result = AiToolResult.fail("exec_failed", msgOf(ex));
            }
            long execLatency = System.currentTimeMillis() - e0;
            if (runningMarkerId != null) try { stepMapper.deleteById(runningMarkerId); } catch (Exception ignore) {}

            // 写工具: 补一条结果审计(发起+结果 双流水)
            if (!tool.readOnly()) {
                auditService.record(ctx == null ? null : ctx.getUserName(), "AI_AGENT_WRITE_RESULT", "POST",
                        "/api/ai/agent/tool/" + toolName, ctx == null ? null : ctx.getIp(),
                        result.isOk() ? 200 : 500, cap(toJson(result), 2000));
                // 写失败留痕(防 AI 谎报成功): 终答尾部如实追加 footer
                if (!result.isOk()) {
                    st.hadWriteFailure = true;
                    st.writeFailureNote = toolName + "→" + (result.getType() == null ? "失败" : result.getType());
                } else if (tool.risk() != null && tool.risk() != com.datanote.platform.ai.agent.tool.RiskLevel.LOW) {
                    // 验证回写飞轮(R7, 借鉴 Vanna/WrenAI): 成功的中/高风险写操作 → 异步沉淀高置信操作技能, 同类意图优先召回正确工具+参数形态
                    aiMemoryService.learnVerifiedAction(userMessage, ctx == null ? null : ctx.getUserName(), toolName, argsToStr(argsNode));
                }
            }

            appendTrace(st, toolName, callJson, result);
            // CRITIC 工具增强自校验(借鉴 CRITIC, arXiv 2305.11738): HIGH 写成功后提示 agent 调只读工具查真值核实再报成功,
            // 把"内省式自纠"升级为"对真值验证"(数据平台 DDL/行数/血缘可验), 防仅凭返回值谎报成功。每类写工具只提示一次。
            if (!tool.readOnly() && result.isOk()
                    && tool.risk() == com.datanote.platform.ai.agent.tool.RiskLevel.HIGH
                    && critiqueNudged.add(toolName)) {
                st.trace.append("【自校验提示】写操作 ").append(toolName)
                        .append(" 已执行成功; 在向用户断言成功前, 请调用只读工具核实真实结果")
                        .append("(如建表/同步后用 asset_detail 或 table_profile 核对表存在与行数, 建质量规则后用 quality_score 看影响面), 勿仅凭返回值即报成功。\n");
            }
            // 连续错误熔断(借鉴 Cline): 连续多步工具失败 → 提示换思路/ask_user, 防在错误里空转烧预算
            if (result.isOk()) {
                consecToolFails[0] = 0;
            } else if (++consecToolFails[0] >= 3) {
                st.trace.append("【连续失败提示】已连续 ").append(consecToolFails[0])
                        .append(" 步工具失败; 请停止重复同类尝试, 改换方案")
                        .append(cronMode ? ", 或如实记录失败原因并结束本次自治运行(定时模式不可询问用户)。\n"
                                         : ", 或调用 ask_user 让用户补充信息/确认方向。\n");
                consecToolFails[0] = 0;
            }
            // 表数据预览: 用【未截断】的原始结果走独立通道回传(stepsToDto 的 resultData 会被 cap 截断, 宽表 JSON 解析不出),
            // 让前端能完整渲染数据表格
            if (result.isOk() && result.getData() instanceof Map && (((Map<?, ?>) result.getData()).containsKey("_preview") || ((Map<?, ?>) result.getData()).containsKey("_chart"))) {
                previews.add((Map<String, Object>) result.getData());
            }
            String resultData = toJson(result);
            newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, think, toolName, argsToStr(argsNode),
                    cap(resultData, STORE_RESULT_CAP),
                    result.getStatus(), result.getType(), tool.readOnly(), tool.risk() == null ? "HIGH" : tool.risk().name(), execLatency));
        }

        // 达上限仍无终答 → 收尾
        if (!st.done && !st.blocked && !st.awaitingApproval && !st.awaitingInput) {
            boolean wallclock = System.currentTimeMillis() >= runDeadline;
            if (wallclock) {
                // 墙钟超时: 不再发 LLM(可能正卡), 直接兜底, 防继续占线程
                st.finalAnswer = "本次任务耗时过长已收尾，已收集到部分信息。可换更聚焦的问法或稍后重试。";
                st.done = true; st.exitReason = "WALLCLOCK_EXHAUSTED";
                newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, null, null, null, null, "ok", null, true, "LOW", null));
            } else {
                String context = promptBuilder.build(userMessage, manifest, st.trace.toString(), today, bizCtxText, ragText, memoryText);
                String raw = callLlmWithRetry(
                        "已达步数上限，请基于以上已获取的信息直接给出最终中文答复，不要再调用工具。", context);
                String think = AgentTextUtil.extractThink(raw);
                String cleaned = isAiError(raw) ? null : AgentTextUtil.cleanFinal(raw);
                st.finalAnswer = isAiError(raw)
                        ? "已收集到部分信息但收尾应答失败，请稍后重试或换种问法。"
                        : (cleaned == null || cleaned.trim().isEmpty()
                            // 模型在收尾轮仍只吐工具调用(被 stripToolCalls 清空): 给长任务分步引导, 不泄漏 JSON、不空答
                            ? "本次任务步骤较多，已完成部分工序(见上方执行过程)。请说『继续』我接着做下一步；逐层建 DWD→DWS→ADS 时, 分步推进(先建 DWD, 再 DWS, 再 ADS)更稳。"
                            : cleaned);
                st.done = true; st.exitReason = budget.remaining() <= 0 ? "BUDGET_EXHAUSTED" : "HARD_CAP_EXHAUSTED";
                newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, think, null, null, null, "ok", null, true, "LOW", null));
            }
        }

        // 写失败防谎报: 终答尾部如实追加 footer(对建表/同步等有副作用工序尤关键)
        if (st.hadWriteFailure && st.finalAnswer != null) {
            st.finalAnswer += "\n\n⚠ 注意：本次有写操作执行失败(" + st.writeFailureNote + ")，实际未生效，请核对后重试。";
        }

        // 落库会话终态(等输入→wait_input, 待审批→wait_approval, 中断→cancelled, 其余 blocked/done)
        String finalStatus = st.awaitingInput ? "wait_input"
                : (st.awaitingApproval ? "wait_approval"
                : ("INTERRUPTED".equals(st.exitReason) ? "cancelled" : (st.blocked ? "blocked" : "done")));
        // 定向更新: 仅改 status/budget/updated_at, 不用 updateById(陈旧 session 会把 todo 写的 plan_json / drainSteer 清的 steer_text 按旧值回写)
        // 任务终态(非 wait_input/wait_approval): 清本任务批量自动批准开关, 边界=只当前任务; 下个新任务重新逐个审批
        boolean taskEnded = !"wait_input".equals(finalStatus) && !"wait_approval".equals(finalStatus);
        UpdateWrapper<DnAiSession> finUw = new UpdateWrapper<DnAiSession>()
                .eq("session_id", session.getSessionId())
                .set("status", finalStatus)
                .set("budget_steps_used", st.seq)
                .set("updated_at", LocalDateTime.now());
        if (taskEnded) finUw.set("auto_approve", 0);
        sessionMapper.update(null, finUw);
        // 批量任务收尾: 已批未执行的审批标记已执行, 防后续 resume 重放与本次 run 内联执行双跑
        if (taskEnded) try { approvalGate.markSessionExecuted(session.getSessionId()); } catch (Exception ignore) {}
        if (st.exitReason != null) {
            log.info("[agent] run 退出 session={} reason={} iter={} budget={}/{} steps={}",
                    session.getSessionId(), st.exitReason, iter, budget.used(), MAX_PRODUCTIVE_STEPS, st.seq);
        }

        // 自学习: 成功完成且有实质工具调用时, 异步蒸馏经验入库+向量化(cron 无人值守不写记忆防噪声)
        if (st.done && !st.blocked && !st.awaitingApproval && !cronMode && hasToolCall(newSteps)) {
            try {
                aiMemoryService.learn(session.getSessionId(), session.getGoalIntent(),
                        ctx == null ? null : ctx.getUserName(), st.trace.toString(), st.finalAnswer);
                // W6 后台复盘: 仅复杂会话(≥3工具)才值得复盘改进点, 异步不阻塞
                if (toolCallCount(newSteps) >= 3) {
                    aiMemoryService.reviewAsync(session.getSessionId(), session.getGoalIntent(),
                            ctx == null ? null : ctx.getUserName(), st.trace.toString(), st.finalAnswer);
                }
            } catch (Exception e) {
                log.warn("[memory] 触发 learn/review 失败 session={}: {}", session.getSessionId(), e.getMessage());
            }
        }

        // 回传最终计划(供前端任务清单面板渲染); 单次再读取最新 plan_json
        try {
            DnAiSession fin = sessionMapper.selectOne(new QueryWrapper<DnAiSession>()
                    .eq("session_id", session.getSessionId()).last("LIMIT 1"));
            if (fin != null && fin.getPlanJson() != null && !fin.getPlanJson().isEmpty()) {
                resp.put("plan", fin.getPlanJson());
            }
        } catch (Exception ignore) {}
        if (st.awaitingInput && st.pendingQuestions != null) {
            resp.put("questions", st.pendingQuestions); // 透传问题清单, 前端渲染决策/协助卡片
        }
        resp.put("sessionId", session.getSessionId());
        resp.put("status", finalStatus);
        resp.put("exitReason", st.exitReason);
        resp.put("finalAnswer", st.finalAnswer != null ? st.finalAnswer
                : (st.blockReason != null ? st.blockReason : "（无答复）"));
        resp.put("steps", stepsToDto(newSteps));
        if (!previews.isEmpty()) resp.put("previews", previews); // 表数据预览(前端渲染数据表格)
        // SSE(特性C): 推送 done 事件(状态+退出原因), 前端据此收尾(无订阅者 no-op)
        try {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("status", finalStatus);
            done.put("exitReason", st.exitReason);
            eventBus.emit(session.getSessionId(), "done", done);
        } catch (Exception ignore) {}
        return resp;
        } finally {
            try { eventBus.complete(session.getSessionId()); } catch (Exception ignore) {} // 关闭并清理 SSE 连接
            _lk.unlock(); // 释放会话级互斥
        }
    }

    /**
     * /goal 有界自驱(W3): 围绕目标连续推进多个周期(每周期一次完整 run), 直到 agent 宣告达成 /
     * 停滞(本周期无工具进展) / 非 done(挂起/阻塞/中断) / 达周期上限。每周期受 run 的 budget+墙钟约束, 故有界。
     */
    public Map<String, Object> pursue(String sessionId, String goal, AgentContext ctx, int maxCycles) {
        int cycles = Math.max(1, Math.min(maxCycles, 3)); // 上限 3, 防长占线程
        Map<String, Object> last = null;
        String sid = sessionId;
        int ran = 0;
        for (int c = 0; c < cycles; c++) {
            String msg = (c == 0) ? goal
                    : "继续推进上述目标。若目标已【全部达成】, 请在答复开头明确写『目标已达成』并给出总结; 否则继续执行下一步, 不要重复已做过的事。";
            last = run(sid, msg, ctx);
            ran++;
            if (last == null) break;
            sid = String.valueOf(last.get("sessionId"));
            if (ctx != null) ctx.setSessionId(sid);
            String status = String.valueOf(last.get("status"));
            String fa = last.get("finalAnswer") == null ? "" : String.valueOf(last.get("finalAnswer"));
            if (!"done".equals(status)) break;          // 挂起/阻塞/中断: 停, 交人处理
            if (fa.contains("目标已达成")) break;          // agent 宣告完成
            if (countTools(last) == 0) break;            // 停滞: 本周期没调工具=无新进展
        }
        if (last != null) last.put("cycles", ran);
        return last;
    }

    /** 统计一次 run 响应里的实质工具调用数。 */
    @SuppressWarnings("unchecked")
    private int countTools(Map<String, Object> resp) {
        Object steps = resp == null ? null : resp.get("steps");
        if (!(steps instanceof List)) return 0;
        int n = 0;
        for (Object o : (List<Object>) steps) {
            if (o instanceof Map) {
                Map<String, Object> s = (Map<String, Object>) o;
                if ("SKILL_CALL".equals(s.get("stepType")) && s.get("skillName") != null) n++;
            }
        }
        return n;
    }

    // ============ 恢复执行: 按已批 args 重放(消除 LLM 漂移, 保证"批准的即执行的") ============

    /**
     * 恢复执行: 不再重跑 LLM 主循环, 而是把本会话【已批准且未执行】的写动作按 step_seq 顺序、
     * 以审批时的原始 args 精确重放(fail-closed 写前审计 + invoke + executed_at 置位防重复),
     * 末尾一次 LLM 收尾汇报。彻底消除"resume 重规划导致 args 漂移/反复审批/执行未批参数"的缺陷。
     */
    public Map<String, Object> resume(String sessionId, AgentContext ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();
        DnAiSession session = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (session == null) {
            resp.put("status", "error");
            resp.put("finalAnswer", "会话不存在");
            resp.put("steps", new ArrayList<>());
            return resp;
        }
        if (!ownerOk(session, ctx)) { // 越权: 不可触发他人会话的写动作重放
            resp.put("status", "blocked");
            resp.put("finalAnswer", "无权恢复该会话(越权隔离)。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }
        List<DnAiApproval> approved = approvalMapper.selectList(new QueryWrapper<DnAiApproval>()
                .eq("session_id", sessionId).eq("status", "approved").isNull("executed_at")
                .orderByAsc("step_seq").orderByAsc("id"));
        if (approved == null || approved.isEmpty()) {
            resp.put("sessionId", sessionId);
            resp.put("status", session.getStatus());
            resp.put("finalAnswer", "没有待执行的已批准写操作(可能已执行或被拒绝)。");
            resp.put("steps", new ArrayList<>());
            return resp;
        }

        // 重放身份: 写入归属(createdBy/owner)取【会话发起人】, 审计 actor 记【实际触发 resume 者】, 二者分离防冒名。
        AgentContext writeCtx = new AgentContext(session.getUserName(), ctx == null ? null : ctx.getIp(), null, sessionId, null);
        permResolver.resolveInto(writeCtx, session.getUserName());   // 重放以会话发起人权限校验
        String actor = ctx == null ? null : ctx.getUserName();

        AgentState st = new AgentState();
        st.session = session;
        st.seq = nextSeq(sessionId);
        seedPriorTrace(st);
        List<DnAiStep> newSteps = new ArrayList<>();
        int executed = 0;

        for (DnAiApproval ap : approved) {
            AiTool tool = toolRegistry.find(ap.getSkillName());
            if (tool == null) { markExecuted(ap); continue; }
            String riskName = tool.risk() == null ? "HIGH" : tool.risk().name();
            // 防御: 重放仅限写工具且非永久禁区(审批记录本不应出现禁区/只读, 双保险)
            if (tool.readOnly() || Guardrail.gate(tool) == Guardrail.Gate.DENY) { markExecuted(ap); continue; }
            // 重放再校验功能权限: 会话发起人若已被回收该权限, 不再执行(防权限变更后旧审批越权落地)
            if (PermGate.check(tool, writeCtx) == PermGate.Decision.DENY) {
                AiToolResult r = AiToolResult.fail("permission_denied", "发起人已无 " + tool.requiredPerm() + " 权限, 跳过重放");
                appendTrace(st, tool.name(), ap.getArgsJson(), r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, tool.name(), ap.getArgsJson(),
                        null, "error", "permission_denied", false, riskName, null));
                markExecuted(ap);   // 标记已处理, 不反复重试
                continue;
            }
            JsonNode argsNode = null;
            try {
                argsNode = (ap.getArgsJson() == null || ap.getArgsJson().isEmpty())
                        ? null : objectMapper.readTree(ap.getArgsJson());
            } catch (Exception ignore) { /* 脏 args 下方校验拦截 */ }
            String vErr = Validation.validate(argsNode, tool.paramsSchemaJson(), objectMapper);
            if (vErr != null) {
                AiToolResult r = AiToolResult.fail("bad_arguments", vErr);
                appendTrace(st, tool.name(), ap.getArgsJson(), r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, tool.name(), ap.getArgsJson(),
                        null, "error", "bad_arguments", false, riskName, null));
                markExecuted(ap);
                continue;
            }
            // fail-closed 写前审计 + 回读(未落库拒执行, 不置 executed 以便下次重试); actor=实际触发者
            Long auditId = auditService.recordReturning(actor, "AI_AGENT_WRITE", "POST",
                    "/api/ai/agent/tool/" + tool.name(), ctx == null ? null : ctx.getIp(), null,
                    "replay by=" + actor + " owner=" + session.getUserName() + " args=" + cap(ap.getArgsJson(), 2000));
            if (!auditService.existsById(auditId)) {
                AiToolResult r = AiToolResult.fail("audit_failed", "写前审计未落库, 拒绝执行");
                appendTrace(st, tool.name(), ap.getArgsJson(), r);
                newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, tool.name(), ap.getArgsJson(),
                        null, "error", "audit_failed", false, riskName, null));
                continue;
            }
            // 原子占行: 仅把 executed_at 从 null→now 抢到者执行, 防并发 resume 重复 invoke(at-most-once)
            int claimed = approvalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnAiApproval>()
                    .eq("id", ap.getId()).eq("status", "approved").isNull("executed_at")
                    .set("executed_at", LocalDateTime.now()));
            if (claimed == 0) {
                continue; // 已被并发 resume 领走, 跳过不重复执行
            }
            AiToolResult result;
            long e0 = System.currentTimeMillis();
            try {
                result = tool.invoke(argsNode, writeCtx); // 写入归属取会话发起人身份
            } catch (Exception ex) {
                result = AiToolResult.fail("exec_failed", msgOf(ex));
            }
            long lat = System.currentTimeMillis() - e0;
            auditService.record(actor, "AI_AGENT_WRITE_RESULT", "POST",
                    "/api/ai/agent/tool/" + tool.name(), ctx == null ? null : ctx.getIp(),
                    result.isOk() ? 200 : 500, cap(toJson(result), 2000));
            if (!result.isOk()) { // 写失败防谎报(与 run() 对称): 重放是真正落地路径, 尤需如实
                st.hadWriteFailure = true;
                st.writeFailureNote = tool.name() + "→" + (result.getType() == null ? "失败" : result.getType());
            }
            appendTrace(st, tool.name(), ap.getArgsJson(), result);
            newSteps.add(writeStep(st, "SKILL_CALL", "tool", null, null, tool.name(), ap.getArgsJson(),
                    cap(toJson(result), STORE_RESULT_CAP), result.getStatus(), result.getType(), false, riskName, lat));
            executed++;
        }

        // 一次 LLM 收尾汇报(不再调用工具)
        String manifest = toolRegistry.toToolsManifestJson();
        String today = LocalDate.now().toString();
        String context = promptBuilder.build(session.getGoalIntent(), manifest, st.trace.toString(), today, null, null, null);
        String raw = aiAssistService.isAvailable()
                ? callLlmWithRetry("以上为已通过人工审批并按原始参数执行的写操作结果。请用中文向用户简要汇报: 做了什么、成功或失败、关键产出(ID/名称)与后续建议。不要再调用任何工具。", context)
                : null;
        String think = AgentTextUtil.extractThink(raw);
        st.finalAnswer = isAiError(raw)
                ? ("已执行 " + executed + " 个已审批写操作。") : AgentTextUtil.sanitize(AgentTextUtil.stripThink(raw));
        if (st.hadWriteFailure && st.finalAnswer != null) { // 写失败防谎报 footer(与 run() 对称, 须在写 FINAL 前)
            st.finalAnswer += "\n\n⚠ 注意：本次有写操作执行失败(" + st.writeFailureNote + ")，实际未生效，请核对后重试。";
        }
        st.done = true;
        newSteps.add(writeStep(st, "FINAL", "assistant", st.finalAnswer, think, null, null, null, "ok", null, true, "LOW", null));

        // 定向更新: 仅改 status/budget/updated_at, 不用 updateById(防旧快照把并发 steer_text/plan_json/interrupt_flag 整行回写, 与 run() 收尾一致)
        sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId)
                .set("status", "done")
                .set("budget_steps_used", st.seq)
                .set("auto_approve", 0)
                .set("updated_at", LocalDateTime.now()));

        if (executed > 0) {
            try {
                aiMemoryService.learn(sessionId, session.getGoalIntent(),
                        session.getUserName(), st.trace.toString(), st.finalAnswer);
            } catch (Exception e) {
                log.warn("[memory] resume 触发 learn 失败 session={}: {}", sessionId, e.getMessage());
            }
        }

        resp.put("sessionId", sessionId);
        resp.put("status", "done");
        resp.put("finalAnswer", st.finalAnswer);
        resp.put("steps", stepsToDto(newSteps));
        return resp;
    }

    /**
     * 批量审批并续跑(只当前任务): 批准本会话所有待审写操作 + 置 auto_approve=1, 再重驱 agent 循环,
     * 后续写操作免逐个审批一路执行到底。安全: 仅短路审批门, PermGate(功能权限)/DataAcl(数据权限) 仍逐个拦截;
     * 任务 done 时自动清 auto_approve(见 run/resume 收尾), 故仅本任务生效。
     */
    public Map<String, Object> approveAllAndContinue(String sessionId, AgentContext ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();
        DnAiSession session = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (session == null) {
            resp.put("status", "error"); resp.put("finalAnswer", "会话不存在"); resp.put("steps", new ArrayList<>());
            return resp;
        }
        if (!ownerOk(session, ctx)) { // 越权隔离: 仅本人(或admin/匿名态)可对自己 agent 开批量自批
            resp.put("status", "blocked"); resp.put("finalAnswer", "无权操作该会话(越权隔离)。"); resp.put("steps", new ArrayList<>());
            return resp;
        }
        String actor = ctx == null ? null : ctx.getUserName();
        // 批准本会话所有待审项(留痕 decided_by=实际触发者)
        approvalMapper.update(null, new UpdateWrapper<DnAiApproval>()
                .eq("session_id", sessionId).eq("status", "pending")
                .set("status", "approved").set("decided_by", actor).set("decided_at", LocalDateTime.now()));
        // 开启本任务批量自动批准(任务 done 时由 run/resume 收尾清 0)
        sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("auto_approve", 1).set("updated_at", LocalDateTime.now()));
        // 重驱循环: 续跑剩余所有步骤, 写操作免逐个审批
        return run(sessionId,
                "已开启本任务批量自动批准。请继续执行计划中所有剩余步骤(逐层 ODS→DWD→DWS→ADS), 一路做到全部完成, 无需再逐个等待审批。",
                ctx);
    }

    /** 标记审批已执行(防 resume 重复执行同一写动作)。 */
    private void markExecuted(DnAiApproval ap) {
        try {
            ap.setExecutedAt(LocalDateTime.now());
            approvalMapper.updateById(ap);
        } catch (Exception ignore) {
        }
    }

    /** 把既往步骤的工具结果喂进 trace, 让 resume 收尾汇报连贯(取近 12 条带结果的步骤)。 */
    private void seedPriorTrace(AgentState st) {
        try {
            List<DnAiStep> prior = stepMapper.selectList(new QueryWrapper<DnAiStep>()
                    .eq("session_id", st.session.getSessionId())
                    .orderByDesc("seq").last("LIMIT 12")); // 取最近12条(原ASC误取最早)
            if (prior == null || prior.isEmpty()) return;
            StringBuilder h = new StringBuilder("（本会话此前步骤）\n");
            for (int i = prior.size() - 1; i >= 0; i--) { // 反转回正序, 保 trace 时间顺序连贯
                DnAiStep s = prior.get(i);
                if (s == null) continue;
                if (s.getSkillName() != null) {
                    h.append("- 工具 ").append(s.getSkillName()).append(" → ")
                            .append(s.getResultStatus() == null ? "" : s.getResultStatus());
                    if (s.getResultData() != null) h.append(": ").append(cap(s.getResultData(), 300));
                    h.append('\n');
                }
            }
            st.trace.append(h).append('\n');
        } catch (Exception e) {
            log.warn("seedPriorTrace 失败 session={}: {}", st.session.getSessionId(), e.getMessage());
        }
    }

    // ============ 会话/步骤 ============

    private DnAiSession loadOrInit(String sessionId, String userMessage, AgentContext ctx) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            DnAiSession exist = sessionMapper.selectOne(
                    new QueryWrapper<DnAiSession>().eq("session_id", sessionId.trim()).last("LIMIT 1"));
            if (exist != null) {
                if (!ownerOk(exist, ctx)) throw new SecurityException("无权访问该会话(越权隔离)");
                exist.setStatus("running");
                exist.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(exist);
                return exist;
            }
        }
        DnAiSession s = new DnAiSession();
        // 客户端可预生成 sessionId(供发起即轮询实时显示动作); 格式合法才用, 否则服务端生成
        String sid = (sessionId != null && sessionId.trim().matches("[0-9a-zA-Z]{8,64}"))
                ? sessionId.trim() : UUID.randomUUID().toString().replace("-", "");
        s.setSessionId(sid);
        s.setUserName(ctx != null ? ctx.getUserName() : null);
        s.setGoalIntent(cap(userMessage, 2000));
        s.setStatus("running");
        s.setInterruptFlag(0);
        s.setBudgetStepsUsed(0);
        s.setVersion(0);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    private int nextSeq(String sessionId) {
        Long cnt = stepMapper.selectCount(new QueryWrapper<DnAiStep>().eq("session_id", sessionId));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** seed 既往会话的 FINAL 摘要进 trace（多轮记忆，最多近 3 条）。 */
    private void seedHistory(AgentState st) {
        try {
            List<DnAiStep> prior = stepMapper.selectList(new QueryWrapper<DnAiStep>()
                    .eq("session_id", st.session.getSessionId())
                    .eq("step_type", "FINAL")
                    .orderByDesc("seq").last("LIMIT 3"));
            if (prior == null || prior.isEmpty()) return;
            StringBuilder h = new StringBuilder("（历史对话摘要）\n");
            for (int i = prior.size() - 1; i >= 0; i--) {
                DnAiStep s = prior.get(i);
                if (s != null && s.getContent() != null) {
                    // 最近一轮答复(i==0, prior 按 seq 降序)给足额度: 多层设计/方案常达数千字,
                    // 砍到 300 字会丢失上一轮产出 → 用户说"执行/继续"时 agent 无据可依而跑偏; 更早两条仍摘要
                    int lim = (i == 0) ? 3000 : 300;
                    h.append("- 先前答复: ").append(cap(s.getContent(), lim)).append('\n');
                }
            }
            st.trace.append(h).append('\n');
        } catch (Exception e) {
            log.warn("seed 历史失败 session={}: {}", st.session.getSessionId(), e.getMessage());
        }
    }

    /** 续跑保连贯: 取最近 FINAL 之后的在途工具步结果 seed 回 trace(供 ask_user 答后/中断后继续时有上下文)。 */
    private void seedContinuation(AgentState st) {
        try {
            DnAiStep lastFinal = stepMapper.selectOne(new QueryWrapper<DnAiStep>()
                    .eq("session_id", st.session.getSessionId()).eq("step_type", "FINAL")
                    .orderByDesc("seq").last("LIMIT 1"));
            int afterSeq = (lastFinal == null || lastFinal.getSeq() == null) ? -1 : lastFinal.getSeq();
            List<DnAiStep> inflight = stepMapper.selectList(new QueryWrapper<DnAiStep>()
                    .eq("session_id", st.session.getSessionId())
                    .gt("seq", afterSeq).orderByAsc("seq").last("LIMIT 20"));
            if (inflight == null || inflight.isEmpty()) return;
            StringBuilder h = new StringBuilder();
            boolean any = false;
            for (DnAiStep s : inflight) {
                if (s == null || !"SKILL_CALL".equals(s.getStepType()) || s.getSkillName() == null) continue;
                h.append("- 工具 ").append(s.getSkillName()).append(" → ")
                        .append(s.getResultStatus() == null ? "" : s.getResultStatus());
                if (s.getResultData() != null) h.append(": ").append(cap(s.getResultData(), 400));
                h.append('\n');
                any = true;
            }
            if (any) st.trace.append("（本任务此前已获取的信息）\n").append(h).append('\n');
        } catch (Exception e) {
            log.warn("seedContinuation 失败 session={}: {}", st.session.getSessionId(), e.getMessage());
        }
    }

    private void appendTrace(AgentState st, String toolName, String callJson, AiToolResult result) {
        st.trace.append("步骤").append(st.seq).append(" 调用工具 ").append(toolName)
                .append(" 参数").append(cap(callJson, 400))
                .append(" → ").append(result.getStatus());
        if (result.isOk()) {
            Object data = result.getData();
            // 空结果显式标注(SWE-agent ACI: 给 LLM 明确的"无数据"反馈, 防臆造内容或对空结果反复重查)
            if (isEmptyResult(data)) {
                st.trace.append(": (空结果/无匹配数据 — 据此判断, 勿臆造内容; 必要时换查询条件, 或如实告知用户暂无)");
            } else
            // 表数据预览: trace 不喂全部行(已经数据表格直接展示给用户), 只留紧凑摘要, 省大量 token 提速
            if (data instanceof Map && ((Map<?, ?>) data).containsKey("_preview")) {
                Map<?, ?> dm = (Map<?, ?>) data;
                Object pv = dm.get("_preview");
                Object cols = (pv instanceof Map) ? ((Map<?, ?>) pv).get("columns") : null;
                st.trace.append(": 已取 ").append(dm.get("table")).append(" ").append(dm.get("returned"))
                        .append(" 行(数据表已直接展示给用户, 答复里不必再逐行罗列; 若用户还要求导出/下载等后续动作请继续完成), 列: ").append(cap(toJson(cols), 3000));
            } else {
                // 字段清单紧凑化(asset_detail 等): 整张列表压成 name(type)[注释] 形式 —— 这是有界且对建模必需的语义,
                // 【永不截断】(本身已是 caveman 式压缩), 宽表 84/200 字段也完整给到 agent, 根除"仅提取前N字段/字段被截断"
                String compactCols = compactColumnsIfAny(data);
                if (compactCols != null) {
                    st.trace.append(": ").append(compactCols);
                } else {
                    String full = AgentTextUtil.redactSecrets(toJson(result.getData())); // trace 喂 learn/reflect/compress, 脱敏防凭据扩散
                    st.trace.append(": ").append(cap(full, TRACE_RESULT_CAP));
                    // 大结果折叠: trace 仅留预览, 提示可按 seq 取全量(超大工具结果落盘取数侧)
                    if (full.length() > TRACE_RESULT_CAP) {
                        st.trace.append(" (结果较大已折叠, 需全量可 read_tool_result(seq=").append(st.seq).append("))");
                    }
                }
            }
        } else {
            st.trace.append("(").append(result.getType()).append("): ").append(cap(result.getMessage(), 400));
        }
        st.trace.append('\n');
    }

    /** 工具结果是否"空"(null/空Map/空集合/空串): 用于给 LLM 明确的无数据反馈。 */
    private static boolean isEmptyResult(Object data) {
        if (data == null) return true;
        if (data instanceof Map) return ((Map<?, ?>) data).isEmpty();
        if (data instanceof java.util.Collection) return ((java.util.Collection<?>) data).isEmpty();
        if (data instanceof CharSequence) return ((CharSequence) data).length() == 0;
        return false;
    }

    /** 若结果含字段清单(asset_detail 的 detail.columns / 顶层 columns), 压成紧凑 name(type)[注释] 串(宽表也不折叠), 否则返回 null。 */
    private String compactColumnsIfAny(Object data) {
        try {
            JsonNode n = objectMapper.valueToTree(data);
            JsonNode cols = n.path("detail").path("columns");
            JsonNode tableNode = n.path("detail").path("table");
            if (!cols.isArray()) { cols = n.path("columns"); tableNode = n.path("table"); }
            if (!cols.isArray() || cols.size() == 0) return null;
            String tname = firstNonEmpty(tableNode.path("tableName").asText(null), tableNode.path("table_name").asText(null));
            StringBuilder sb = new StringBuilder();
            sb.append("表元数据+字段清单(").append(cols.size()).append("字段");
            if (tname != null) sb.append(", 表=").append(tname);
            String comment = firstNonEmpty(tableNode.path("tableComment").asText(null), tableNode.path("table_comment").asText(null));
            if (comment != null && comment.length() < 60) sb.append(", 表注释=").append(comment);
            sb.append("): ");
            for (int i = 0; i < cols.size(); i++) {
                JsonNode c = cols.get(i);
                String cn = firstNonEmpty(c.path("columnName").asText(null), c.path("column_name").asText(null), "?");
                String dt = firstNonEmpty(c.path("dataType").asText(null), c.path("data_type").asText(null));
                String cm = firstNonEmpty(c.path("columnComment").asText(null), c.path("comment").asText(null));
                if (i > 0) sb.append(", ");
                sb.append(cn);
                if (dt != null) sb.append('(').append(dt).append(')');
                if (cm != null && cm.length() < 30) sb.append('[').append(cm).append(']');
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static String firstNonEmpty(String... vs) {
        if (vs == null) return null;
        for (String v : vs) if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        return null;
    }

    private DnAiStep writeStep(AgentState st, String stepType, String role, String content, String think,
                               String skillName, String argsJson, String resultData,
                               String resultStatus, String resultType, boolean readOnly, String risk, Long latency) {
        DnAiStep step = new DnAiStep();
        step.setSessionId(st.session.getSessionId());
        step.setSeq(st.seq++);
        step.setStepType(stepType);
        step.setRole(role);
        step.setContent(content == null ? null : AgentTextUtil.sanitize(content));
        step.setThinkContent(think == null ? null : AgentTextUtil.sanitize(think));
        step.setSkillName(skillName);
        AiTool t = skillName == null ? null : toolRegistry.find(skillName);
        step.setSkillGroup(t != null ? t.group() : null);
        step.setArgsJson(argsJson == null ? null : AgentTextUtil.redactSecrets(argsJson)); // 持久化禁凭据红线
        step.setResultStatus(resultStatus);
        step.setResultType(resultType);
        step.setResultData(resultData == null ? null : AgentTextUtil.redactSecrets(AgentTextUtil.sanitize(resultData)));
        step.setReadOnly(readOnly ? 1 : 0);
        step.setRiskLevel(risk);
        step.setLatencyMs(latency);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        // SSE(特性C): 实时推送步骤事件(无订阅者 no-op)
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("seq", step.getSeq());
            ev.put("type", step.getStepType());
            ev.put("skill", step.getSkillName());
            ev.put("status", step.getResultStatus());
            eventBus.emit(st.session.getSessionId(), "step", ev);
        } catch (Exception ignore) {}
        return step;
    }

    /** 调用前插入 RUNNING 进度标记(seq 复用当前值不自增, 用后即删), 让前端轮询实时显示"正在{工具}", 长耗时工具界面不致"卡住"。 */
    private Long writeRunningMarker(AgentState st, String toolName) {
        try {
            DnAiStep m = new DnAiStep();
            m.setSessionId(st.session.getSessionId());
            m.setSeq(st.seq);
            m.setStepType("RUNNING");
            m.setRole("tool");
            m.setSkillName(toolName);
            AiTool t = toolName == null ? null : toolRegistry.find(toolName);
            m.setSkillGroup(t != null ? t.group() : null);
            m.setReadOnly(1);
            m.setRiskLevel("LOW");
            m.setCreatedAt(LocalDateTime.now());
            stepMapper.insert(m);
            try { eventBus.emit(st.session.getSessionId(), "running", java.util.Collections.singletonMap("skill", toolName)); } catch (Exception ignore) {}
            return m.getId();
        } catch (Exception e) { return null; }
    }

    private List<Map<String, Object>> stepsToDto(List<DnAiStep> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DnAiStep s : steps) {
            if (s == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("stepType", s.getStepType());
            m.put("role", s.getRole());
            m.put("content", s.getContent());
            m.put("thinkContent", s.getThinkContent());
            m.put("skillName", s.getSkillName());
            m.put("skillGroup", s.getSkillGroup());
            m.put("argsJson", s.getArgsJson());
            m.put("resultStatus", s.getResultStatus());
            m.put("resultType", s.getResultType());
            m.put("resultData", s.getResultData());
            m.put("readOnly", s.getReadOnly());
            m.put("riskLevel", s.getRiskLevel());
            m.put("latencyMs", s.getLatencyMs());
            out.add(m);
        }
        return out;
    }

    /** 把业务情境 bizCtx 渲染为中文一段(供 prompt 注入)，缺省返回 null。 */
    private String buildBizCtxText(Map<String, Object> bizCtx) {
        if (bizCtx == null || bizCtx.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        Object route = bizCtx.get("route");
        if (route != null) sb.append("用户当前在『").append(routeLabel(String.valueOf(route))).append("』模块。");
        Object db = bizCtx.get("db"), table = bizCtx.get("table");
        if (db != null && table != null) sb.append("正在查看数据表 ").append(db).append('.').append(table).append('。');
        else if (table != null) sb.append("正在查看表 ").append(table).append('。');
        Object ruleId = bizCtx.get("ruleId");
        if (ruleId != null) sb.append("关注质量规则 ruleId=").append(ruleId).append('。');
        Object jobId = bizCtx.get("jobId"), jobName = bizCtx.get("jobName");
        if (jobId != null) sb.append("关注同步任务 #").append(jobId).append(jobName != null ? ("(" + jobName + ")") : "").append('。');
        Object runId = bizCtx.get("runId"), taskName = bizCtx.get("taskName");
        if (runId != null) sb.append("关注调度运行 run#").append(runId).append(taskName != null ? ("(" + taskName + ")") : "").append('。');
        Object metricId = bizCtx.get("metricId");
        if (metricId != null) sb.append("关注指标 metricId=").append(metricId).append('。');
        Object taskId = bizCtx.get("taskId"), taskType = bizCtx.get("taskType");
        if (taskId != null) sb.append("关注调度任务 taskId=").append(taskId).append(taskType != null ? ("(类型" + taskType + ")") : "").append('。');
        Object projectId = bizCtx.get("projectId");
        if (projectId != null) sb.append("关注项目 projectId=").append(projectId).append('。');
        // 主动血缘: 当前表有 db+table 时, 一上来就注入下游影响面(让 agent 即知联动关系, 用上图/血缘库)
        if (db != null && table != null) {
            String lin = buildDownstreamHint(String.valueOf(db), String.valueOf(table));
            if (lin != null) sb.append(lin);
        }
        sb.append("回答时优先围绕该情境；如涉及具体表/规则/任务，在结论中用 [表:库.表] / [规则:#id] / [任务:#id] 标记以便用户一键跳转。");
        String s = sb.toString().trim();
        return s.isEmpty() ? null : AgentTextUtil.sanitize(s);
    }

    /** 资产 RAG + 文档知识库 RAG 合并注入首轮 prompt(文档按发起人 owner 隔离)。 */
    private String buildRagText(String query, String owner) {
        String asset = buildAssetRagText(query);
        String doc = buildDocText(query, owner);
        if (asset == null) return doc;
        if (doc == null) return asset;
        return asset + "\n\n" + doc;
    }

    /** 文档知识库 RAG: 召回用户上传文档相关片段, 渲染"相关文档"段。无命中/向量不可用返 null。 */
    @SuppressWarnings("unchecked")
    private String buildDocText(String query, String owner) {
        try {
            Map<String, Object> r = docIngestService.searchDocs(query, owner, DOC_TOPK);
            Object resObj = r == null ? null : r.get("results");
            if (!(resObj instanceof List)) return null;
            List<Map<String, Object>> results = (List<Map<String, Object>>) resObj;
            if (results.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("# 相关文档片段(来自我上传的文档, 据此作答并注明来源文件名)\n");
            for (Map<String, Object> m : results) {
                if (m == null) continue;
                Object fn = m.get("file_name"), txt = m.get("text");
                if (txt == null) continue;
                String t = String.valueOf(txt).replaceAll("\\s+", " ").trim();
                if (t.length() > 300) t = t.substring(0, 300) + "…";
                sb.append("- 《").append(fn == null ? "?" : fn).append("》: ").append(t).append('\n');
                if (sb.length() >= 1600) break;
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : AgentTextUtil.sanitize(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** RAG: 向量召回与目标相关的资产, 渲染为线索文本注入首轮 prompt; 仅向量引擎命中才注入(关键字噪音大跳过), 异常返 null 降级。 */
    @SuppressWarnings("unchecked")
    private String buildAssetRagText(String query) {
        try {
            Map<String, Object> rag = semanticSearchService.search(query, null, RAG_TOPK);
            if (rag == null || !"vector".equals(rag.get("engine"))) return null;
            Object resObj = rag.get("results");
            if (!(resObj instanceof List)) return null;
            List<Map<String, Object>> results = (List<Map<String, Object>>) resObj;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> r : results) {
                if (r == null) continue;
                Object name = r.get("name");
                if (name == null) continue;
                Object db = r.get("db"), title = r.get("title"), score = r.get("score");
                Object kind = r.get("kind");
                String k = String.valueOf(kind);
                sb.append("- ").append(kindLabel(kind)).append(' ');
                if ("column".equals(k)) {
                    // 字段: 显示 字段名 (表 db.table)
                    sb.append(name);
                    Object ctab = r.get("table");
                    if (ctab != null && !String.valueOf(ctab).trim().isEmpty()) {
                        sb.append(" (表 ");
                        if (db != null && !String.valueOf(db).trim().isEmpty()) sb.append(db).append('.');
                        sb.append(ctab).append(')');
                    }
                } else {
                    if (db != null && !String.valueOf(db).trim().isEmpty()) sb.append(db).append('.');
                    sb.append(name);
                }
                if (title != null && !String.valueOf(title).trim().isEmpty()) {
                    sb.append("glossary".equals(k) ? "：" : ("metric".equals(k) ? " 口径：" : " — ")).append(title);
                }
                if (score != null) {
                    try { sb.append(" (相关度 ").append(String.format("%.2f", Double.parseDouble(String.valueOf(score)))).append(")"); }
                    catch (Exception ignore) {}
                }
                sb.append('\n');
                if (sb.length() >= RAG_TEXT_CAP) break;
            }
            // GraphRAG: 对语义召回的首张表附其上下游血缘邻居(向量召回→图扩展, grounding 带关系网络)
            String linNet = topTableLineage(results);
            if (linNet != null) sb.append(linNet);
            String s = sb.toString().trim();
            return s.isEmpty() ? null : AgentTextUtil.sanitize(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 数据中心已上传文件清单(让 agent 感知, 可 file_read 读取分析)。无文件返 null。 */
    private String buildFilesText(String owner) {
        try {
            List<com.datanote.platform.ai.agent.model.DnAiFile> files = aiFileService.list(owner);
            if (files == null || files.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (com.datanote.platform.ai.agent.model.DnAiFile f : files) {
                if (f == null) continue;
                sb.append("- id=").append(f.getId()).append(" ").append(f.getFileName())
                        .append(" (").append(f.getSizeBytes() == null ? 0 : f.getSizeBytes()).append("字节")
                        .append("agent".equals(f.getSource()) ? ", AI生成" : "").append(")\n");
                if (++n >= 15) { sb.append("…(更多见数据中心)\n"); break; }
            }
            return sb.length() == 0 ? null : sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** GraphRAG: 取语义结果首张表, 拉其上下游血缘邻居渲染为一行(向量召回×图扩展)。无表/无边返 null。 */
    private String topTableLineage(List<Map<String, Object>> results) {
        try {
            for (Map<String, Object> r : results) {
                if (r == null || !"table".equals(String.valueOf(r.get("kind")))) continue;
                Object db = r.get("db"), name = r.get("name");
                if (db == null || name == null) continue;
                String us = joinNodes(lineageEdgeService.trace(String.valueOf(db), String.valueOf(name)), 5);
                String ds = joinNodes(lineageEdgeService.impact(String.valueOf(db), String.valueOf(name)), 5);
                if (us == null && ds == null) return null;
                StringBuilder sb = new StringBuilder("  ↳ ").append(db).append('.').append(name).append(" 血缘网络: ");
                if (us != null) sb.append("上游[").append(us).append("] → ");
                sb.append("本表");
                if (ds != null) sb.append(" → 下游[").append(ds).append("]");
                sb.append('\n');
                return sb.toString();
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** 血缘节点列表 → "db.table、db.table…"(截断 max)。空返 null。 */
    private static String joinNodes(List<Map<String, Object>> nodes, int max) {
        if (nodes == null || nodes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, Object> node : nodes) {
            if (node == null) continue;
            Object d = node.get("db"), t = node.get("table");
            if (t == null) continue;
            if (n > 0) sb.append('、');
            if (d != null) sb.append(d).append('.');
            sb.append(t);
            if (++n >= max) { sb.append('…'); break; }
        }
        return n == 0 ? null : sb.toString();
    }

    /** 主动注入当前表下游血缘(改动影响面), 让 agent 一上来即知联动关系(用上图/血缘库)。异常/无边返 null。 */
    private String buildDownstreamHint(String db, String table) {
        try {
            List<Map<String, Object>> ds = lineageEdgeService.impact(db, table);
            if (ds == null || ds.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("该表下游血缘(改动需评估影响): ");
            int n = 0;
            for (Map<String, Object> node : ds) {
                if (node == null) continue;
                Object ndb = node.get("db"), ntab = node.get("table");
                if (ntab == null) continue;
                if (n > 0) sb.append("、");
                if (ndb != null) sb.append(ndb).append('.');
                sb.append(ntab);
                if (++n >= 8) { sb.append(" 等"); break; }
            }
            sb.append("。");
            return n == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染会话计划(plan_json)为可读清单注入 prompt; 空返 null。 */
    private String renderPlan(String planJson) {
        if (planJson == null || planJson.trim().isEmpty()) return null;
        try {
            JsonNode n = objectMapper.readTree(planJson);
            JsonNode steps = n.isArray() ? n : n.get("steps");
            if (steps == null || !steps.isArray() || steps.size() == 0) return null;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (JsonNode s : steps) {
                String step = s.path("step").asText("");
                String status = s.path("status").asText("pending");
                String mark = "done".equals(status) ? "[✓]" : ("doing".equals(status) ? "[▶]" : "[ ]");
                sb.append(mark).append(' ').append(i++).append(". ").append(step).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 原子排空中途转向插话: 仅当 steer_text 仍等于 cur 时清空(防并发覆盖丢失), 抢到返 cur 否则 null。 */
    private String drainSteer(String sessionId, String cur) {
        if (cur == null || cur.trim().isEmpty()) return null;
        try {
            int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                    .eq("session_id", sessionId).eq("steer_text", cur).set("steer_text", null));
            return n > 0 ? cur : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 清中断标志(消费后置 0, 防下次重入误判)。 */
    private void clearInterrupt(String sessionId) {
        try {
            sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                    .eq("session_id", sessionId).set("interrupt_flag", 0));
        } catch (Exception ignore) {
        }
    }

    /** 语义召回结果 kind → 中文标签(知识库多类型可读化)。 */
    private static String kindLabel(Object kind) {
        if (kind == null) return "数据表";
        switch (String.valueOf(kind)) {
            case "table": return "数据表";
            case "glossary": return "业务术语";
            case "metric": return "指标";
            case "column": return "字段";
            case "standard": case "dataelement": return "数据标准";
            case "wordroot": return "词根";
            default: return String.valueOf(kind);
        }
    }

    private String routeLabel(String route) {
        switch (route == null ? "" : route) {
            case "catalog": return "数据地图";
            case "governance": return "数据治理";
            case "dbsync": return "数据同步";
            case "operations": return "数据运维";
            case "develop": return "数据开发";
            case "metrics": return "指标管理";
            case "mdm": return "主数据";
            case "project": return "项目管理";
            case "quality": return "数据质量";
            case "home": return "首页";
            default: return route;
        }
    }

    /**
     * 反思自检(借鉴 Reflexion): 仅当本轮已有 ≥2 次工具证据时, 让 LLM 把答复草稿与『已执行步骤与工具结果』
     * 对齐, 修正未被证据支撑或与证据矛盾的论断(降幻觉); 已严谨则基本保持。单次额外 LLM 调用, 任何错误/空返回退回草稿。
     */
    private String reflectIfNeeded(String draft, List<DnAiStep> steps, String goal, String manifest,
                                   String trace, String today, String bizCtx, String rag, String memory) {
        if (draft == null || draft.trim().isEmpty()) return draft;
        if (toolCallCount(steps) < 2) return draft; // 单工具/无工具不值得反思, 省一次调用
        try {
            String ctx = promptBuilder.build(goal, manifest, trace, today, bizCtx, rag, memory);
            String reflected = callLlmWithRetry(
                    "以下是你对用户问题的答复草稿:\n" + cap(draft, 1500)
                    + "\n\n请对照上方『已执行步骤与工具结果』做【反幻觉自检】: 逐一核对草稿里每个具体的库名/表名/字段名/数值/口径, "
                    + "是否都能在工具结果里找到【出处】? 凡是找不到出处的, 一律【删除或改为『未查到/不确定』】, 严禁保留臆造内容; "
                    + "若与证据矛盾则按证据更正; 若关键信息缺失就如实告知用户缺什么、建议怎么补(或提示需要 ask_user 澄清)。"
                    + "直接输出修正后的最终中文答复, 不要再调用任何工具, 不要解释你改了什么。", ctx);
            if (isAiError(reflected)) return draft;
            String r = AgentTextUtil.cleanFinal(reflected);
            return (r == null || r.trim().isEmpty()) ? draft : r;
        } catch (Exception e) {
            return draft;
        }
    }

    /** 本轮实质工具调用次数。 */
    private int toolCallCount(List<DnAiStep> steps) {
        int c = 0;
        if (steps != null) for (DnAiStep s : steps) {
            if (s != null && "SKILL_CALL".equals(s.getStepType()) && s.getSkillName() != null) c++;
        }
        return c;
    }

    /** 本轮是否有实质工具调用(决定是否值得沉淀经验)。 */
    private boolean hasToolCall(List<DnAiStep> steps) {
        if (steps == null) return false;
        for (DnAiStep s : steps) {
            if (s != null && "SKILL_CALL".equals(s.getStepType()) && s.getSkillName() != null) return true;
        }
        return false;
    }

    private String argsToStr(JsonNode args) {
        return args == null ? null : cap(args.toString(), 2000);
    }

    private String toJson(Object o) {
        if (o == null) return "";
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }

    /**
     * 调 LLM 一次性重试(借鉴 hermes turn_retry_state 的一次性 guard + retry_utils 抖动退避)。
     * 首调若为瞬时错(AI请求失败/空返回/格式异常), 抖动退避后重试 1 次; 仍错则返回首个错误串。
     * 单次 DeepSeek 抖动不再直接拖垮整轮 agent 运行。
     */
    /** 流式首轮调用(特性C): chatStream 逐字回调 → 发 token 事件; chatStream 内部已含失败回退非流式。 */
    private String callLlmStreaming(String userPrompt, String context, String sid) {
        try {
            return aiAssistService.chatStream(userPrompt, context,
                    tok -> eventBus.emit(sid, "token", java.util.Collections.singletonMap("t", tok)));
        } catch (Exception e) {
            return callLlmWithRetry(userPrompt, context);
        }
    }

    private String callLlmWithRetry(String userPrompt, String context) {
        String raw = aiAssistService.chat(userPrompt, context);
        if (!isAiError(raw)) return raw;
        // 按错误分类决定恢复策略(借鉴 hermes error_classifier)
        ErrorClassifier.Action act = ErrorClassifier.classify(raw);
        if (act == ErrorClassifier.Action.AUTH || act == ErrorClassifier.Action.ABORT
                || act == ErrorClassifier.Action.CONTEXT_OVERFLOW) {
            return raw; // 鉴权/未配置重试无意义; 超窗交外层 forceCompress 后重试(同 context 重试必再超窗)
        }
        // 限流加长退避(attempt=2), 其余一般退避(attempt=1)
        long backoff = act == ErrorClassifier.Action.RATE_LIMIT ? backoffMillis(2) : backoffMillis(1);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return raw;
        }
        String retry = aiAssistService.chat(userPrompt, context);
        return isAiError(retry) ? raw : retry;
    }

    /** 抖动退避(借鉴 hermes retry_utils.jittered_backoff): base*2^(n-1) 封顶, 叠加 [0,0.5d) 抖动去相关。 */
    private static long backoffMillis(int attempt) {
        long base = 800L, max = 8000L;
        int exp = Math.max(0, attempt - 1);
        long delay = exp >= 20 ? max : Math.min(base * (1L << exp), max);
        return delay + (long) (Math.random() * 0.5 * delay);
    }

    /** AiAssistService.chat 的错误前缀统一判定(未配置/请求失败/格式异常/空)。 */
    private static boolean isAiError(String raw) {
        return raw == null
                || raw.startsWith("AI 功能未配置")
                || raw.startsWith("AI 请求失败")
                || raw.equals("AI 返回格式异常");
    }

    /** 取异常可读信息: message 为空(如 NPE)时退化为异常类名, 避免问题被 null 掩盖。 */
    private static String msgOf(Throwable e) {
        if (e == null) return "unknown_error";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
