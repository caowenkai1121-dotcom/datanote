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
        return R.ok(aiAgentService.run(sessionId, message.trim(), ctx));
    }

    /** 查会话与全量步骤轨迹（可回放）。 */
    @GetMapping("/session/{id}")
    public R<Map<String, Object>> session(@PathVariable("id") String id) {
        DnAiSession s = sessionMapper.selectOne(
                new QueryWrapper<DnAiSession>().eq("session_id", id).last("LIMIT 1"));
        if (s == null) {
            return R.fail("会话不存在");
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

    /** 待审批清单(审批抽屉数据源)。 */
    @GetMapping("/approvals")
    public R<List<DnAiApproval>> approvals(@RequestParam(value = "status", required = false, defaultValue = "pending") String status) {
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

    /** 恢复执行: 重入 run, 命中已批审批点放行写动作落地。 */
    @PostMapping("/{sessionId}/resume")
    public R<Map<String, Object>> resume(@PathVariable("sessionId") String sessionId, HttpServletRequest req) {
        DnAiSession s = sessionMapper.selectOne(new QueryWrapper<DnAiSession>().eq("session_id", sessionId).last("LIMIT 1"));
        if (s == null) return R.fail("会话不存在");
        String goal = (s.getGoalIntent() == null || s.getGoalIntent().trim().isEmpty()) ? "继续之前的任务" : s.getGoalIntent();
        AgentContext ctx = new AgentContext(currentUser(), clientIp(req), null, sessionId, null);
        return R.ok(aiAgentService.run(sessionId, goal, ctx));
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
