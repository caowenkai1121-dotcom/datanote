package com.datanote.platform.ai.agent.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.common.model.R;
import com.datanote.common.util.ClientIpUtil;
import com.datanote.platform.ai.agent.engine.AiAgentService;
import com.datanote.platform.ai.agent.mapper.DnAiApprovalMapper;
import com.datanote.platform.ai.agent.mapper.DnAiMemorySkillMapper;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
import com.datanote.platform.ai.agent.model.DnAiApproval;
import com.datanote.platform.ai.agent.model.DnAiMemorySkill;
import com.datanote.platform.ai.agent.model.DnAiSession;
import com.datanote.platform.ai.agent.model.DnAiStep;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 天工·自由意志数据智能体 Controller（M1：发起对话/查会话/列工具）。 */
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
public class AiAgentController {

    private final AiAgentService aiAgentService;
    private final AiToolRegistry toolRegistry;
    private final DnAiSessionMapper sessionMapper;
    private final DnAiStepMapper stepMapper;
    private final DnAiApprovalMapper approvalMapper;
    private final DnAiMemorySkillMapper memoryMapper;
    private final com.datanote.platform.ai.agent.mapper.DnAiCronJobMapper cronMapper;
    private final ObjectMapper objectMapper;
    private final com.datanote.platform.ai.agent.engine.AgentPermResolver permResolver;
    private final com.datanote.platform.iam.RbacService rbacService;
    private final com.datanote.platform.ai.agent.engine.AgentEventBus eventBus;
    private final com.datanote.platform.ai.agent.engine.AiProfileService aiProfileService;

    private final com.datanote.platform.ai.AiAssistService aiAssistService;

    @org.springframework.beans.factory.annotation.Value("${datanote.ai.auto-default-steps:300}")
    private int autoDefaultSteps;
    @org.springframework.beans.factory.annotation.Value("${datanote.ai.auto-default-hours:2}")
    private double autoDefaultHours;

    /** 发起一轮：body {sessionId?, message, ctx?:{route,db,table,...}}，返回 {sessionId, status, finalAnswer, steps}。 */
    @PostMapping("/chat")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> chat(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String message = (body == null || body.get("message") == null) ? null : String.valueOf(body.get("message"));
        String sessionId = (body == null || body.get("sessionId") == null) ? null : String.valueOf(body.get("sessionId"));
        if (message == null || message.trim().isEmpty()) {
            return R.fail("消息不能为空");
        }
        Object ctxObj = body == null ? null : body.get("ctx");
        Map<String, Object> bizCtx = (ctxObj instanceof Map) ? (Map<String, Object>) ctxObj : null;
        AgentContext ctx = buildCtx(sessionId, bizCtx, req);
        // 模型热切: 本请求覆盖模型档位(同 provider), 边界 set/clear 防泄漏到其它请求
        String modelOverride = body == null || body.get("model") == null ? null : String.valueOf(body.get("model"));
        com.datanote.platform.ai.AiAssistService.setModelOverride(modelOverride);
        try {
            return R.ok(aiAgentService.run(sessionId, message.trim(), ctx));
        } finally {
            com.datanote.platform.ai.AiAssistService.clearModelOverride();
        }
    }

    /** /goal 有界自驱: 围绕目标连续推进多周期(每周期受 budget+墙钟约束), 达成/停滞/上限即止。body {message, sessionId?, maxCycles?}。 */
    @PostMapping("/pursue")
    public R<Map<String, Object>> pursue(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String message = (body == null || body.get("message") == null) ? null : String.valueOf(body.get("message"));
        if (message == null || message.trim().isEmpty()) return R.fail("目标不能为空");
        String sessionId = (body == null || body.get("sessionId") == null) ? null : String.valueOf(body.get("sessionId"));
        int maxCycles = 2;
        try { if (body != null && body.get("maxCycles") != null) maxCycles = Integer.parseInt(String.valueOf(body.get("maxCycles"))); } catch (Exception ignore) {}
        AgentContext ctx = buildCtx(sessionId, null, req);
        return R.ok(aiAgentService.pursue(sessionId, message.trim(), ctx, maxCycles));
    }

    /** SSE 流式事件订阅(特性C): 前端发起 /chat 前打开, 实时收 step/running/token/approval/question/done。
     *  纯附加: SSE 不可用/断开时前端回退 1.5s 轮询(兜底)。会话尚未创建时放行(随后 /chat 以同 sid 创建)。 */
    @GetMapping("/stream/{sessionId}")
    public SseEmitter stream(@PathVariable("sessionId") String sessionId) {
        DnAiSession s = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (s != null) {
            String me = currentUser();
            if (me != null && !"anonymous".equals(me) && !"admin".equals(me)
                    && s.getUserName() != null && !"anonymous".equals(s.getUserName()) && !me.equals(s.getUserName())) {
                SseEmitter em = new SseEmitter(0L);
                try { em.send(SseEmitter.event().name("error").data("无权访问该会话")); em.complete(); } catch (Exception ignore) {}
                return em; // 越权: 即时关闭
            }
        }
        return eventBus.register(sessionId);
    }

    /** 查会话与全量步骤轨迹（可回放）。 */
    @GetMapping("/session/{id}")
    public R<Map<String, Object>> session(@PathVariable("id") String id) {
        DnAiSession s = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", id).last("LIMIT 1"));
        if (s == null) {
            return R.fail("会话不存在");
        }
        // 越权隔离: 仅会话发起人可读其轨迹(匿名态放行)
        String me = currentUser();
        if (me != null && !"anonymous".equals(me) && !"admin".equals(me)
                && s.getUserName() != null && !"anonymous".equals(s.getUserName()) && !me.equals(s.getUserName())) {
            return R.fail("无权访问该会话");
        }
        List<DnAiStep> steps = stepMapper.selectList(
                new QueryWrapper<DnAiStep>().eq("session_id", id).orderByAsc("seq"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", s);
        m.put("steps", steps);
        return R.ok(m);
    }

    /**
     * 列出【当前用户自己】的历史会话(会话隔离: 严格按 user_name=本人, admin 也只看自己, 匿名态看匿名)。
     * 返回精简列表供左侧历史面板渲染; 轨迹详情走 /session/{id}(同款归属校验)。
     */
    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> sessions(@RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        String me = currentUser();
        if (me == null) return R.ok(new ArrayList<>());
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        List<DnAiSession> list = sessionMapper.selectList(new QueryWrapper<DnAiSession>()
                .eq("user_name", me)                       // 严格限本人: 用户只能返回自己的记录
                .ne("status", "deleted")                   // 排除用户已删除的历史
                .orderByDesc("updated_at").orderByDesc("id")
                .last("LIMIT " + limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DnAiSession s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("title", s.getGoalIntent());             // 标题=会话目标/意图(为空时前端兜底)
            m.put("status", s.getStatus());
            m.put("autonomous", s.getAutonomous());        // 自主运行中标记(前端显示🚀)
            m.put("updatedAt", s.getUpdatedAt());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 当前用户的【用户画像】(用户隔离的长久记忆; 经验抽屉展示)。 */
    @GetMapping("/user-profile")
    public R<Map<String, Object>> userProfile() {
        String me = currentUser();
        com.datanote.platform.ai.agent.model.DnAiUserProfile p = aiProfileService.getUserProfile(me);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userName", me);
        m.put("content", p == null ? null : p.getContent());
        m.put("updatedAt", p == null ? null : p.getUpdatedAt());
        return R.ok(m);
    }

    /** 手动触发画像汇总(运维/测试用, 异步; 仅登录用户)。 */
    @PostMapping("/profile-digest/run")
    public R<Void> runProfileDigest() {
        String me = currentUser();
        if (me == null || "anonymous".equals(me)) return R.fail("请登录后再触发");
        aiProfileService.runDailyDigestAsync();
        return R.ok();
    }

    /** 【项目画像】(全局长久记忆; 经验抽屉展示)。 */
    @GetMapping("/project-profile")
    public R<Map<String, Object>> projectProfile() {
        com.datanote.platform.ai.agent.model.DnAiProjectProfile p = aiProfileService.getProjectProfile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", p == null ? null : p.getContent());
        m.put("updatedAt", p == null ? null : p.getUpdatedAt());
        return R.ok(m);
    }

    /** Agent 运维健康快照: 工具数 / 待审批数 / 运行中(含自主)会话数。便于监控与排障。 */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        try { m.put("aiConfigured", aiAssistService.isAvailable()); } catch (Exception e) { m.put("aiConfigured", false); }
        m.put("tools", toolRegistry.size());
        try { m.put("pendingApprovals", approvalMapper.selectCount(new QueryWrapper<DnAiApproval>().eq("status", "pending"))); } catch (Exception e) { m.put("pendingApprovals", -1); }
        try { m.put("runningSessions", sessionMapper.selectCount(new QueryWrapper<DnAiSession>().eq("status", "running"))); } catch (Exception e) { m.put("runningSessions", -1); }
        try { m.put("autonomousSessions", sessionMapper.selectCount(new QueryWrapper<DnAiSession>().eq("autonomous", 1).eq("status", "running"))); } catch (Exception e) { m.put("autonomousSessions", -1); }
        try { m.put("todayActiveSessions", sessionMapper.selectCount(new QueryWrapper<DnAiSession>().ge("updated_at", java.time.LocalDate.now().atStartOfDay()))); } catch (Exception e) { m.put("todayActiveSessions", -1); }
        m.put("autoDefaultSteps", autoDefaultSteps);
        m.put("autoDefaultHours", autoDefaultHours);
        return R.ok(m);
    }

    /** 列出已注册工具（机读清单）。 */
    @GetMapping("/tools")
    public R<Map<String, Object>> tools() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", toolRegistry.size());
        try {
            m.put("tools", objectMapper.readTree(toolRegistry.toToolsManifestJson()));
        } catch (Exception e) {
            m.put("tools", toolRegistry.toToolsManifestJson());
        }
        return R.ok(m);
    }

    /** AI 自学习记忆清单(active, 按命中+近因)。让"AI 数据入库入表、留记忆"可见。 */
    @GetMapping("/memories")
    public R<List<DnAiMemorySkill>> memories(@RequestParam(value = "status", required = false, defaultValue = "active") String status) {
        return R.ok(memoryMapper.selectList(new QueryWrapper<DnAiMemorySkill>()
                .eq("status", status).orderByDesc("hit_count").orderByDesc("updated_at").last("LIMIT 200")));
    }

    /** 待审批清单(审批抽屉数据源); 按 owner 作用域过滤(仅含本人会话的审批, 匿名/开放态看全部)。 */
    @GetMapping("/approvals")
    public R<List<DnAiApproval>> approvals(@RequestParam(value = "status", required = false, defaultValue = "pending") String status) {
        String me = ownerScope();
        if (me != null) {
            List<DnAiSession> mySessions = sessionMapper.selectList(
                    new QueryWrapper<DnAiSession>().select("session_id").eq("user_name", me));
            List<String> sessionIds = new java.util.ArrayList<>();
            for (DnAiSession s : mySessions) sessionIds.add(s.getSessionId());
            if (sessionIds.isEmpty()) return R.ok(java.util.Collections.emptyList());
            return R.ok(approvalMapper.selectList(new QueryWrapper<DnAiApproval>()
                    .eq("status", status).in("session_id", sessionIds).orderByDesc("id").last("LIMIT 100")));
        }
        return R.ok(approvalMapper.selectList(new QueryWrapper<DnAiApproval>()
                .eq("status", status).orderByDesc("id").last("LIMIT 100")));
    }

    /** 审批决策: decided_by 服务端强制 + 禁自批 + 乐观 where status=pending 防竞态。 */
    @PostMapping("/approval/{id}/decide")
    public R<Void> decide(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        String decision = body == null ? null : body.get("decision");
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            return R.fail("decision 须为 approved 或 rejected");
        }
        DnAiApproval ap = approvalMapper.selectById(id);
        if (ap == null) return R.fail("审批不存在");
        if (!"pending".equals(ap.getStatus())) return R.fail("该审批已处理");
        String me = currentUser();
        DnAiSession s = sessionMapper.selectOne(new QueryWrapper<DnAiSession>().eq("session_id", ap.getSessionId()).last("LIMIT 1"));
        // 会话归属校验(P1): 只能审批自己发起的 Agent 操作(设计本意=用户确认本人 agent);
        // 防有权限的他人按审批 id 批他人 agent 动作。admin 与开放/匿名态例外(与 assertSessionOwner 一致)。
        if (me != null && !"anonymous".equals(me) && !"admin".equals(me)
                && s != null && s.getUserName() != null && !"anonymous".equals(s.getUserName())
                && !me.equals(s.getUserName())) {
            return R.fail("无权审批他人发起的 Agent 操作");
        }
        // 权限对齐: 允许用户确认自己 agent 的动作(行使本人权限), 但确认人须真正拥有该写工具所需权限点。
        if ("approved".equals(decision)) {
            com.datanote.platform.ai.agent.tool.AiTool t = toolRegistry.find(ap.getSkillName());
            String need = t == null ? null : t.requiredPerm();
            if (need == null && t != null && !t.readOnly()) need = "*"; // 漏标权限点的写工具: 视为需超管才能批准(fail-closed)
            if (need != null && me != null && !"anonymous".equals(me)) {
                java.util.Set<String> myPerms;
                try { myPerms = rbacService.getUserPermsByUsername(me); } catch (Exception e) { myPerms = java.util.Collections.emptySet(); }
                if (!com.datanote.platform.iam.RbacService.hasPermission(myPerms, need)) {
                    return R.fail("无该写操作权限(需要 " + need + "), 不能批准");
                }
            }
        }
        int rows = approvalMapper.update(null, new UpdateWrapper<DnAiApproval>()
                .eq("id", id).eq("status", "pending")
                .set("status", decision).set("decided_by", me).set("decided_at", LocalDateTime.now()));
        if (rows == 0) return R.fail("审批已被处理(竞态)");
        // 会话状态: 批准→paused(待恢复); 自主会话批准→running(后台驱动器自动续驱, 无需手动 resume); 拒绝→blocked
        if (s != null) {
            String ns = "approved".equals(decision)
                    ? (Integer.valueOf(1).equals(s.getAutonomous()) ? "running" : "paused")
                    : "blocked";
            s.setStatus(ns);
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
        return R.ok();
    }

    /** 用户回答 ask_user 卡片: 把选择注入为续跑消息, agent 继续完成原任务(seed 在途上下文保连贯)。 */
    @PostMapping("/{sessionId}/answer")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> answer(@PathVariable("sessionId") String sessionId,
                                         @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Object ansObj = body == null ? null : body.get("answers");
        String msg = formatAnswers(ansObj);
        if (msg == null) return R.fail("answers 不能为空");
        AgentContext ctx = buildCtx(sessionId, null, req);
        return R.ok(aiAgentService.run(sessionId, msg, ctx));
    }

    /** 把卡片回答拼成续跑消息(问→答)。 */
    @SuppressWarnings("unchecked")
    private String formatAnswers(Object answers) {
        if (!(answers instanceof List)) return null;
        List<Object> list = (List<Object>) answers;
        if (list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("（我已在卡片中做出选择）\n");
        boolean any = false;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Object q = m.get("question");
            Object a = m.get("answer");
            if (q == null) continue;
            String ans = (a == null || String.valueOf(a).trim().isEmpty()) ? "(跳过)" : String.valueOf(a);
            sb.append("- 问：").append(q).append("  答：").append(ans).append('\n');
            any = true;
        }
        if (!any) return null;
        sb.append("请据此继续完成原任务。");
        return sb.toString();
    }

    /** 定时自治任务清单(cron 面板数据源); 按 owner 作用域(匿名态看全部)。 */
    @GetMapping("/crons")
    public R<java.util.List<com.datanote.platform.ai.agent.model.DnAiCronJob>> crons() {
        String me = ownerScope();
        return R.ok(cronMapper.selectList(new QueryWrapper<com.datanote.platform.ai.agent.model.DnAiCronJob>()
                .eq(me != null, "owner", me)
                .orderByDesc("enabled").orderByAsc("next_run").last("LIMIT 100")));
    }

    /** 启用/停用定时任务(owner 作用域 + 容错解析)。 */
    @PostMapping("/cron/{id}/toggle")
    public R<Void> toggleCron(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object e = body == null ? null : body.get("enabled");
        Integer enabled = null;
        if (e != null) {
            if (e instanceof Number) enabled = ((Number) e).intValue() == 1 ? 1 : 0;
            else { String s = String.valueOf(e).trim(); enabled = ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) ? 1 : 0; }
        }
        String me = ownerScope();
        com.datanote.platform.ai.agent.model.DnAiCronJob j = cronMapper.selectById(id);
        if (j == null || (me != null && !me.equals(j.getOwner()))) return R.fail("任务不存在");
        int en = enabled != null ? enabled : (j.getEnabled() != null && j.getEnabled() == 1 ? 0 : 1);
        cronMapper.update(null, new UpdateWrapper<com.datanote.platform.ai.agent.model.DnAiCronJob>()
                .eq("id", id).eq(me != null, "owner", me).set("enabled", en).set("updated_at", LocalDateTime.now()));
        return R.ok();
    }

    /** 删除定时任务(owner 作用域)。 */
    @PostMapping("/cron/{id}/remove")
    public R<Void> removeCron(@PathVariable("id") Long id) {
        String me = ownerScope();
        int n = cronMapper.delete(new QueryWrapper<com.datanote.platform.ai.agent.model.DnAiCronJob>()
                .eq("id", id).eq(me != null, "owner", me));
        return n > 0 ? R.ok() : R.fail("任务不存在");
    }

    /** 当前用户作用域: 匿名/未登录/超管返 null(看全部, 兼容鉴权关闭态与超管运维历史匿名任务), 否则返用户名。 */
    private String ownerScope() {
        String me = currentUser();
        return (me == null || "anonymous".equals(me) || "admin".equals(me)) ? null : me;
    }

    /** 重命名自己的历史会话(改 goal_intent 作标题; 仅本人)。 */
    @PostMapping("/{sessionId}/rename")
    public R<Void> renameSession(@PathVariable("sessionId") String sessionId, @RequestBody Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.trim().isEmpty()) return R.fail("标题不能为空");
        R<Void> denied = assertSessionOwner(sessionId);
        if (denied != null) return denied;
        String t = title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("goal_intent", t).set("updated_at", LocalDateTime.now()));
        return n > 0 ? R.ok() : R.fail("会话不存在");
    }

    /** 软删除自己的历史会话(从历史列表隐藏, 同时停掉其自主执行; 仅本人)。 */
    @PostMapping("/{sessionId}/delete")
    public R<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        R<Void> denied = assertSessionOwner(sessionId);
        if (denied != null) return denied;
        int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("status", "deleted").set("autonomous", 0).set("updated_at", LocalDateTime.now()));
        return n > 0 ? R.ok() : R.fail("会话不存在");
    }

    /** 协作式中断: 置中断标志, 运行中的 agent 在下一轮工序边界自行停止(替代SSE的DB标志位)。 */
    @PostMapping("/{sessionId}/interrupt")
    public R<Void> interrupt(@PathVariable("sessionId") String sessionId) {
        R<Void> denied = assertSessionOwner(sessionId);
        if (denied != null) return denied;
        int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("interrupt_flag", 1).set("autonomous", 0).set("updated_at", LocalDateTime.now()));
        return n > 0 ? R.ok() : R.fail("会话不存在");
    }

    /** 中途转向: 写入引导插话, 运行中的 agent 在下一轮工序边界并入上下文(不打断当前轮)。 */
    @PostMapping("/{sessionId}/steer")
    public R<Void> steer(@PathVariable("sessionId") String sessionId, @RequestBody Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        if (text == null || text.trim().isEmpty()) return R.fail("引导内容不能为空");
        R<Void> denied = assertSessionOwner(sessionId);
        if (denied != null) return denied;
        int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("steer_text", text.trim()).set("updated_at", LocalDateTime.now()));
        return n > 0 ? R.ok() : R.fail("会话不存在");
    }

    /** 会话归属校验: 与 session() GET 同款判断(匿名/开放态放行)。非发起人返回拒绝(R.fail), 通过返 null。 */
    private R<Void> assertSessionOwner(String sessionId) {
        DnAiSession s = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (s == null) return R.fail("会话不存在");
        String me = currentUser();
        if (me != null && !"anonymous".equals(me) && !"admin".equals(me)
                && s.getUserName() != null && !"anonymous".equals(s.getUserName()) && !me.equals(s.getUserName())) {
            return R.fail("无权访问该会话");
        }
        return null;
    }

    /** 恢复执行: 按已批 args 精确重放已批准未执行的写动作(不重跑 LLM 规划, 消除 args 漂移)。 */
    @PostMapping("/{sessionId}/resume")
    public R<Map<String, Object>> resume(@PathVariable("sessionId") String sessionId, HttpServletRequest req) {
        AgentContext ctx = buildCtx(sessionId, null, req);
        return R.ok(aiAgentService.resume(sessionId, ctx));
    }

    /** 批量审批并续跑(本任务): 批准本会话待审写操作 + 开启 auto_approve, 续跑剩余步骤免逐个审批(仍受功能/数据权限拦截)。 */
    @PostMapping("/{sessionId}/approve-all")
    public R<Map<String, Object>> approveAll(@PathVariable("sessionId") String sessionId, HttpServletRequest req) {
        AgentContext ctx = buildCtx(sessionId, null, req);
        return R.ok(aiAgentService.approveAllAndContinue(sessionId, ctx));
    }

    /**
     * 启动无人值守自主执行: body {maxSteps?:300, maxHours?:2}。后台驱动器按 todo 计划持续推进至完成/预算耗尽。
     * 常规写自动执行, 高危(HIGH)写仍挂起等批; PermGate/DataAcl 仍逐个拦; 仅本人可启。
     */
    @PostMapping("/{sessionId}/autonomous")
    public R<Map<String, Object>> autonomous(@PathVariable("sessionId") String sessionId,
                                             @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        int maxSteps = autoDefaultSteps;
        long maxMs = (long) (autoDefaultHours * 3600_000L);
        if (body != null) {
            Object ms = body.get("maxSteps");
            if (ms != null) try { maxSteps = Integer.parseInt(String.valueOf(ms)); } catch (Exception ignore) {}
            Object mh = body.get("maxHours");
            if (mh != null) try { maxMs = (long) (Double.parseDouble(String.valueOf(mh)) * 3600_000L); } catch (Exception ignore) {}
        }
        AgentContext ctx = buildCtx(sessionId, null, req);
        return R.ok(aiAgentService.startAutonomous(sessionId, maxSteps, maxMs, ctx));
    }

    /** 构造执行上下文并填充发起人权限快照。 */
    private AgentContext buildCtx(String sessionId, Map<String, Object> bizCtx, HttpServletRequest req) {
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, bizCtx);
        permResolver.resolveInto(ctx, ctx.getUserName());
        return ctx;
    }

    private String currentUser() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getName() != null && !"anonymousUser".equals(a.getName())) {
                return a.getName();
            }
        } catch (Exception ignore) {
            // 无安全上下文（鉴权关闭）→ 匿名
        }
        return "anonymous";
    }

    private String clientIp(HttpServletRequest req) {
        return ClientIpUtil.resolve(req);
    }
}
