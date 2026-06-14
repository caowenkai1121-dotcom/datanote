package com.datanote.platform.ai.agent.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.common.model.R;
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

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, bizCtx);
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
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, null);
        return R.ok(aiAgentService.pursue(sessionId, message.trim(), ctx, maxCycles));
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
        // 防自批提权: 审批人不得为会话发起人(匿名测试态放行)
        DnAiSession s = sessionMapper.selectOne(new QueryWrapper<DnAiSession>().eq("session_id", ap.getSessionId()).last("LIMIT 1"));
        if (s != null && me != null && !"anonymous".equals(me) && me.equals(s.getUserName())) {
            return R.fail("不可自批(审批人须不同于会话发起人)");
        }
        int rows = approvalMapper.update(null, new UpdateWrapper<DnAiApproval>()
                .eq("id", id).eq("status", "pending")
                .set("status", decision).set("decided_by", me).set("decided_at", LocalDateTime.now()));
        if (rows == 0) return R.fail("审批已被处理(竞态)");
        // 会话状态: 批准→paused(待恢复), 拒绝→blocked
        if (s != null) {
            s.setStatus("approved".equals(decision) ? "paused" : "blocked");
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
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, null);
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

    /** 当前用户作用域: 匿名/未登录返 null(看全部, 兼容鉴权关闭态), 否则返用户名。 */
    private String ownerScope() {
        String me = currentUser();
        return (me == null || "anonymous".equals(me)) ? null : me;
    }

    /** 协作式中断: 置中断标志, 运行中的 agent 在下一轮工序边界自行停止(替代SSE的DB标志位)。 */
    @PostMapping("/{sessionId}/interrupt")
    public R<Void> interrupt(@PathVariable("sessionId") String sessionId) {
        R<Void> denied = assertSessionOwner(sessionId);
        if (denied != null) return denied;
        int n = sessionMapper.update(null, new UpdateWrapper<DnAiSession>()
                .eq("session_id", sessionId).set("interrupt_flag", 1).set("updated_at", LocalDateTime.now()));
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
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, null);
        return R.ok(aiAgentService.resume(sessionId, ctx));
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
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
