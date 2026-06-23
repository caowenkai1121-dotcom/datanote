package com.datanote.platform.ai.agent.web;

import com.datanote.common.model.R;
import com.datanote.platform.ai.agent.engine.IndustryKnowledgeService;
import com.datanote.platform.ai.agent.model.DnAiIndustrySop;
import com.datanote.platform.ai.agent.model.DnAiIndustrySopHist;
import com.datanote.platform.ai.agent.model.DnAiProjectProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 行业画像/行业经验 Controller: 行业画像展示 + 业务流程SOP 管理(增改/版本/回滚) + 手动归纳。 */
@RestController
@RequestMapping("/api/ai/agent/industry")
@RequiredArgsConstructor
public class AiIndustryController {

    private final IndustryKnowledgeService industryService;

    /** 行业画像列表(全局概览 + 各业务域), 经验抽屉展示。 */
    @GetMapping("/profiles")
    public R<List<Map<String, Object>>> profiles() {
        List<DnAiProjectProfile> ps = industryService.listIndustryProfiles();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DnAiProjectProfile p : ps) {
            Map<String, Object> m = new LinkedHashMap<>();
            String key = p.getProfileKey() == null ? "" : p.getProfileKey();
            m.put("key", key);
            m.put("domain", key.startsWith("industry_") ? key.substring("industry_".length()) : key);
            m.put("content", p.getContent());
            m.put("updatedAt", p.getUpdatedAt());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 业务流程SOP 列表(按业务域/状态)。 */
    @GetMapping("/sops")
    public R<List<DnAiIndustrySop>> sops(@RequestParam(required = false) String domain,
                                         @RequestParam(required = false) String status) {
        return R.ok(industryService.listSop(domain, status));
    }

    /** SOP 详情。 */
    @GetMapping("/sop/{id}")
    public R<DnAiIndustrySop> sop(@PathVariable Long id) {
        return R.ok(industryService.getSop(id));
    }

    /** SOP 版本历史。 */
    @GetMapping("/sop/{id}/history")
    public R<List<DnAiIndustrySopHist>> history(@PathVariable Long id) {
        return R.ok(industryService.history(id));
    }

    /** 新建/教学 SOP。body {domain,type,title,content,trigger}。 */
    @PostMapping("/sop")
    public R<DnAiIndustrySop> create(@RequestBody Map<String, String> body) {
        if (body == null || isBlank(body.get("title")) || isBlank(body.get("content")))
            return R.fail("title 与 content 必填");
        DnAiIndustrySop s = industryService.saveSop(body.get("domain"), body.get("type"),
                body.get("title"), body.get("content"), body.get("trigger"), "taught", "active", currentUser());
        return R.ok(s);
    }

    /** 更新 SOP(编辑/纠正), 自动版本+历史。body {title,content,op}。 */
    @PostMapping("/sop/{id}")
    public R<DnAiIndustrySop> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        DnAiIndustrySop s = industryService.updateSop(id, body == null ? null : body.get("title"),
                body == null ? null : body.get("content"), body == null ? "edit" : body.get("op"), currentUser());
        return s == null ? R.fail("SOP 不存在") : R.ok(s);
    }

    /** 归档 SOP。 */
    @PostMapping("/sop/{id}/archive")
    public R<Void> archive(@PathVariable Long id) {
        return industryService.archiveSop(id, currentUser()) ? R.ok() : R.fail("SOP 不存在");
    }

    /** 回滚 SOP 到指定版本。body {version}。 */
    @PostMapping("/sop/{id}/rollback")
    public R<Void> rollback(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer v = body == null ? null : toInt(body.get("version"));
        if (v == null) return R.fail("version 必填");
        return industryService.rollbackSop(id, v, currentUser()) ? R.ok() : R.fail("版本不存在");
    }

    /** 手动触发行业画像归纳+蒸馏(异步; 仅登录用户)。 */
    @PostMapping("/digest/run")
    public R<Void> digest() {
        String me = currentUser();
        if (me == null || "anonymous".equals(me)) return R.fail("请登录后再触发");
        industryService.digestIndustryProfilesAsync();
        return R.ok();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static Integer toInt(Object o) {
        if (o == null) return null;
        try { return o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return null; }
    }
    private String currentUser() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getName() != null && !"anonymousUser".equals(a.getName())) return a.getName();
        } catch (Exception ignore) {}
        return "anonymous";
    }
}
