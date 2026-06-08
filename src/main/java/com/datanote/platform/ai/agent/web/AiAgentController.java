package com.datanote.platform.ai.agent.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.model.R;
import com.datanote.platform.ai.agent.engine.AiAgentService;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.mapper.DnAiStepMapper;
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
