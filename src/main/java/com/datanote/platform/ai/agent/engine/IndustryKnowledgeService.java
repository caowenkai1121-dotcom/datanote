package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnSubjectMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.metadata.model.DnSubject;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiIndustrySopHistMapper;
import com.datanote.platform.ai.agent.mapper.DnAiIndustrySopMapper;
import com.datanote.platform.ai.agent.mapper.DnAiProjectProfileMapper;
import com.datanote.platform.ai.agent.model.DnAiIndustrySop;
import com.datanote.platform.ai.agent.model.DnAiIndustrySopHist;
import com.datanote.platform.ai.agent.model.DnAiProjectProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业画像/行业经验服务(天工开物·格物致知 → 经世致用):
 * 把平台沉淀的业务知识(业务域/指标口径/库表/分层/血缘)与 agent 实战经验, 归纳为"行业经验",
 * 注入 agent 上下文, 让小白也能被引导着准确完成业务流与报表开发。
 * 单组织按业务域(dn_subject)分段。行业画像正文复用 dn_ai_project_profile(profile_key='industry_*');
 * 业务流程 SOP 独立存 dn_ai_industry_sop(+版本历史)。降级: AI 不可用则跳过蒸馏, 不阻塞主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryKnowledgeService {

    private final DnAiProjectProfileMapper profileMapper;       // 复用: 存行业画像正文
    private final DnAiIndustrySopMapper sopMapper;
    private final DnAiIndustrySopHistMapper sopHistMapper;
    private final DnSubjectMapper subjectMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnMetricMapper metricMapper;
    private final AiAssistService aiAssistService;

    public static final String GLOBAL_KEY = "industry_global";
    private static final String PREFIX = "industry_";
    private static final int PROFILE_CAP = 1600;     // 单个行业画像正文上限
    private static final int DOMAIN_LIMIT = 12;       // 每日最多蒸馏业务域数(防成本失控)
    private static final int TABLES_PER_DOMAIN = 18;  // 每域纳入素材的表上限
    private static final int COLS_TABLES = 8;         // 每域取字段明细的表上限
    private static final int METRIC_LIMIT = 120;

    // ============ 注入 prompt: 行业画像 + 相关 SOP ============
    /** 取行业画像注入文本: 全局概览 + 与当前问题匹配的业务域画像(选择性, 控 token)。null=无。 */
    public String industryProfileText(String query) {
        List<DnAiProjectProfile> all = profileMapper.selectList(new QueryWrapper<DnAiProjectProfile>()
                .likeRight("profile_key", PREFIX).last("LIMIT 50"));
        if (all == null || all.isEmpty()) return null;
        String q = query == null ? "" : query;
        StringBuilder sb = new StringBuilder();
        // 全局概览常驻
        for (DnAiProjectProfile p : all) {
            if (GLOBAL_KEY.equals(p.getProfileKey()) && notBlank(p.getContent())) {
                sb.append("【行业全局概览】\n").append(p.getContent().trim()).append("\n\n");
            }
        }
        // 与问题相关的业务域画像(域名出现在问题里) → 选择性注入
        int picked = 0;
        for (DnAiProjectProfile p : all) {
            if (GLOBAL_KEY.equals(p.getProfileKey()) || !notBlank(p.getContent())) continue;
            String domain = p.getProfileKey().substring(PREFIX.length());
            if (q.contains(domain) && picked < 3) {
                sb.append("【业务域: ").append(domain).append("】\n").append(p.getContent().trim()).append("\n\n");
                picked++;
            }
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? null : cap(r, PROFILE_CAP * 3); // 封顶注入(全局+最多3域), 控 token
    }

    /** 取与问题相关的业务流程 SOP 文本(注入/工具用)。 */
    public String sopText(String query, int cap) {
        List<DnAiIndustrySop> hits = recallSop(null, query, 5);
        if (hits.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (DnAiIndustrySop s : hits) {
            sb.append("# [").append(typeLabel(s.getSopType())).append("] ").append(nz(s.getTitle()))
              .append(s.getDomain() == null ? "" : "(域:" + s.getDomain() + ")").append("\n")
              .append(cap(s.getContent(), 900)).append("\n\n");
            if (sb.length() > cap) break;
        }
        return sb.toString().trim();
    }

    /** 列出所有行业画像(全局概览 + 各业务域), 供经验抽屉展示。 */
    public List<DnAiProjectProfile> listIndustryProfiles() {
        return profileMapper.selectList(new QueryWrapper<DnAiProjectProfile>()
                .likeRight("profile_key", PREFIX).orderByAsc("profile_key").last("LIMIT 50"));
    }

    /** 异步触发行业画像归纳+蒸馏(运维/手动用, 免等每日 tick)。 */
    @org.springframework.scheduling.annotation.Async("aiDigestExecutor")
    public void digestIndustryProfilesAsync() { digestIndustryProfiles(); }

    private volatile boolean bootstrapping = false; // 自举去重: 防 industry_recall 多次触发重复蒸馏
    /** 首次使用自举: 行业画像为空时自动触发一次归纳(免用户手动点"归纳画像")。 */
    public void bootstrapIfEmpty() {
        if (bootstrapping || !aiAssistService.isAvailable()) return;
        try { if (!listIndustryProfiles().isEmpty()) return; } catch (Exception e) { return; }
        bootstrapping = true;
        try { digestIndustryProfilesAsync(); } catch (Exception e) { bootstrapping = false; }
    }

    // ============ SOP 召回 ============
    /** 召回 SOP: 按 域 + 关键词命中(标题/触发词/正文), 按命中次数+近因排序。命中 hit_count+1。 */
    public List<DnAiIndustrySop> recallSop(String domain, String query, int topN) {
        QueryWrapper<DnAiIndustrySop> qw = new QueryWrapper<DnAiIndustrySop>().eq("status", "active");
        if (notBlank(domain)) qw.and(w -> w.eq("domain", domain).or().eq("domain", "global"));
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            // 取关键词(最长几段)做 LIKE; 简单务实
            String kw = q.length() > 40 ? q.substring(0, 40) : q;
            qw.and(w -> w.like("title", kw).or().like("trigger_hint", kw).or().like("content", kw));
        }
        qw.orderByDesc("hit_count").orderByDesc("updated_at").last("LIMIT " + Math.max(1, topN));
        List<DnAiIndustrySop> list = sopMapper.selectList(qw);
        if (list == null) return new ArrayList<>();
        for (DnAiIndustrySop s : list) {
            try { sopMapper.update(null, new UpdateWrapper<DnAiIndustrySop>().eq("id", s.getId())
                    .setSql("hit_count = hit_count + 1")); } catch (Exception ignore) {}
        }
        return list;
    }

    public List<DnAiIndustrySop> listSop(String domain, String status) {
        QueryWrapper<DnAiIndustrySop> qw = new QueryWrapper<>();
        if (notBlank(domain)) qw.eq("domain", domain);
        qw.eq("status", notBlank(status) ? status : "active");
        qw.orderByDesc("updated_at").last("LIMIT 300");
        return sopMapper.selectList(qw);
    }

    public DnAiIndustrySop getSop(Long id) { return id == null ? null : sopMapper.selectById(id); }

    public List<DnAiIndustrySopHist> history(Long sopId) {
        return sopHistMapper.selectList(new QueryWrapper<DnAiIndustrySopHist>()
                .eq("sop_id", sopId).orderByDesc("version").last("LIMIT 50"));
    }

    // ============ SOP 写入(教学/沉淀/归纳) + 版本 ============
    /** 新建 SOP, 记 v1 历史。 */
    public DnAiIndustrySop saveSop(String domain, String type, String title, String content,
                                   String trigger, String source, String status, String editor) {
        DnAiIndustrySop s = new DnAiIndustrySop();
        s.setDomain(notBlank(domain) ? domain : "global");
        s.setSopType(notBlank(type) ? type : "flow");
        s.setTitle(cap(safe(title), 250));
        s.setContent(safe(content));
        s.setTriggerHint(cap(trigger, 480));
        s.setSource(notBlank(source) ? source : "taught");
        s.setStatus(notBlank(status) ? status : "active");
        s.setVersion(1);
        s.setHitCount(0);
        s.setCreatedBy(editor);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sopMapper.insert(s);
        snapshot(s, "create", editor);
        return s;
    }

    /** 更新 SOP(编辑/对话纠正): version++, 旧版入历史。trigger 为 null 则不改。 */
    public DnAiIndustrySop updateSop(Long id, String title, String content, String trigger, String op, String editor) {
        DnAiIndustrySop s = sopMapper.selectById(id);
        if (s == null) return null;
        snapshot(s, op == null ? "edit" : op, editor); // 先存改前快照
        if (notBlank(title)) s.setTitle(cap(title, 250));
        if (content != null) s.setContent(content);
        if (trigger != null) s.setTriggerHint(cap(trigger, 480));
        s.setVersion((s.getVersion() == null ? 1 : s.getVersion()) + 1);
        s.setUpdatedAt(LocalDateTime.now()); // 保留原 createdBy(创建者), 不被编辑者覆盖
        sopMapper.updateById(s);
        return s;
    }

    public boolean archiveSop(Long id, String editor) {
        DnAiIndustrySop s = sopMapper.selectById(id);
        if (s == null) return false;
        snapshot(s, "archive", editor);
        sopMapper.update(null, new UpdateWrapper<DnAiIndustrySop>().eq("id", id)
                .set("status", "archived").set("updated_at", LocalDateTime.now()));
        return true;
    }

    /** 回滚到指定历史版本。 */
    public boolean rollbackSop(Long id, Integer version, String editor) {
        DnAiIndustrySop s = sopMapper.selectById(id);
        if (s == null) return false;
        DnAiIndustrySopHist h = sopHistMapper.selectOne(new QueryWrapper<DnAiIndustrySopHist>()
                .eq("sop_id", id).eq("version", version).last("LIMIT 1"));
        if (h == null) return false;
        return updateSop(id, h.getTitle(), h.getContent(), null, "rollback", editor) != null;
    }

    private void snapshot(DnAiIndustrySop s, String op, String editor) {
        try {
            DnAiIndustrySopHist h = new DnAiIndustrySopHist();
            h.setSopId(s.getId()); h.setVersion(s.getVersion());
            h.setTitle(s.getTitle()); h.setContent(s.getContent());
            h.setOp(op); h.setEditor(editor); h.setSnapshotAt(LocalDateTime.now());
            sopHistMapper.insert(h);
        } catch (Exception e) { log.warn("[industry] SOP 版本快照失败 sop={} op={}: {}", s == null ? null : s.getId(), op, e.getMessage()); }
    }

    /** 实战自学习: 成功完成的业务流/报表 → 异步沉淀为 SOP 草稿(人工或后续确认转 active)。 */
    @org.springframework.scheduling.annotation.Async("aiDigestExecutor")
    public void learnSopAsync(String domain, String type, String title, String content, String trigger) {
        if (!notBlank(title) || !notBlank(content)) return;
        try {
            // 去重: 同域同标题已存在则跳过(避免刷屏)
            Long cnt = sopMapper.selectCount(new QueryWrapper<DnAiIndustrySop>()
                    .eq("domain", notBlank(domain) ? domain : "global").eq("title", cap(title, 250)));
            if (cnt != null && cnt > 0) return;
            saveSop(domain, type, title,
                    AgentTextUtil.redactSecrets(AgentTextUtil.sanitize(content)),
                    trigger, "learned", "draft", "agent");
        } catch (Exception e) { log.warn("[industry] 沉淀SOP失败: {}", e.getMessage()); }
    }

    // ============ 元数据归纳 + 每日蒸馏行业画像 ============
    /** 每日蒸馏: 全局行业概览 + 各业务域画像(从平台元数据 harvest + 实战SOP)。 */
    public void digestIndustryProfiles() {
        if (!aiAssistService.isAvailable()) { log.info("[industry] AI 未配置, 跳过行业画像蒸馏"); return; }
        long t0 = System.currentTimeMillis();
        // 主题域 id → 名称(用于把表归到业务域)
        Map<Long, String> subjectName = new LinkedHashMap<>();
        try {
            List<DnSubject> subs = subjectMapper.selectList(new QueryWrapper<DnSubject>().last("LIMIT 500"));
            for (DnSubject s : subs) subjectName.put(s.getId(), s.getName());
        } catch (Exception e) { log.warn("[industry] 取主题域失败: {}", e.getMessage()); }

        // 1) 业务域分组: 优先主题域名; 表未挂主题域则回退按库名(database)分域 —— 保证无主题域标注时也能产出分域画像
        Map<String, List<DnTableMeta>> tablesByDomain = new LinkedHashMap<>();
        try {
            List<DnTableMeta> tbls = tableMetaMapper.selectList(new QueryWrapper<DnTableMeta>().last("LIMIT 3000"));
            for (DnTableMeta t : tbls) {
                String dom = t.getSubjectId() != null ? subjectName.get(t.getSubjectId()) : null;
                if (!notBlank(dom)) dom = nz(t.getDatabaseName()); // 回退库名
                if (!notBlank(dom)) continue;
                tablesByDomain.computeIfAbsent(dom, k -> new ArrayList<>()).add(t);
            }
        } catch (Exception e) { log.warn("[industry] 取表元数据失败: {}", e.getMessage()); }

        // 指标(口径) 全局素材
        String metricMaterial = metricMaterial();

        // 域按表数降序
        List<Map.Entry<String, List<DnTableMeta>>> topDomains = new ArrayList<>(tablesByDomain.entrySet());
        topDomains.sort((a, b) -> b.getValue().size() - a.getValue().size());

        // 2) 全局概览蒸馏
        try {
            StringBuilder dm = new StringBuilder("业务域清单(按表数):\n");
            topDomains.stream().limit(30)
                    .forEach(e -> dm.append("- ").append(e.getKey()).append(" (").append(e.getValue().size()).append(" 表)\n"));
            String prompt = "你在为数据开发平台构建【行业全局概览】(全局共享, 帮助新人快速理解业务)。基于以下业务域与关键指标, "
                    + "蒸馏成简明中文概览(≤" + PROFILE_CAP + "字), 覆盖: 主要业务域及其职责、关键业务指标及口径、数仓分层惯例(ODS→DWD→DWS→ADS)、通用命名与开发约定。"
                    + "面向小白可读。只输出正文, 不含密钥。\n\n【业务域】\n" + dm + "\n【关键指标】\n" + cap(metricMaterial, 4000);
            String distilled = distill(prompt);
            if (distilled != null) upsertProfile(GLOBAL_KEY, cap(distilled, PROFILE_CAP));
        } catch (Exception e) { log.warn("[industry] 全局概览蒸馏失败: {}", e.getMessage()); }

        // 3) 各业务域画像蒸馏(取表数最多的前 DOMAIN_LIMIT 个域)
        int done = 0;
        for (Map.Entry<String, List<DnTableMeta>> e : topDomains) {
            if (done >= DOMAIN_LIMIT) break;
            String name = e.getKey();
            if (!notBlank(name)) continue;
            try {
                String material = domainMaterial(name, e.getValue());
                String sopMat = domainSopMaterial(name);
                String prompt = "你在为业务域【" + name + "】构建【行业经验画像】, 让小白能照此准确完成该域的业务流与报表开发。"
                        + "基于该域的核心表/字段、相关指标口径、已沉淀业务流程SOP, 蒸馏成简明中文经验(≤" + PROFILE_CAP + "字), 覆盖: "
                        + "该域核心实体与常用表、关键指标与口径、标准业务流程/加工链路(分层)、报表开发要点、已知坑与注意事项。"
                        + "务实可操作, 面向小白。只输出正文, 不含密钥。\n\n【核心表/字段】\n" + cap(material, 4000)
                        + "\n\n【相关指标口径】\n" + cap(metricMaterial, 1500)
                        + "\n\n【已沉淀SOP】\n" + (sopMat == null ? "(无)" : cap(sopMat, 2000));
                String distilled = distill(prompt);
                if (distilled != null) { upsertProfile(PREFIX + name, cap(distilled, PROFILE_CAP)); done++; }
            } catch (Exception ex) { log.warn("[industry] 业务域画像蒸馏失败 {}: {}", name, ex.getMessage()); }
        }
        log.info("[industry] 行业画像蒸馏完成: 全局 + {} 个业务域, 耗时 {}ms", done, System.currentTimeMillis() - t0);
        bootstrapping = false; // 自举完成, 允许后续按需再触发
    }

    private String metricMaterial() {
        try {
            List<DnMetric> ms = metricMapper.selectList(new QueryWrapper<DnMetric>()
                    .orderByDesc("updated_at").last("LIMIT " + METRIC_LIMIT));
            if (ms == null || ms.isEmpty()) return "(暂无指标)";
            StringBuilder sb = new StringBuilder();
            for (DnMetric m : ms) {
                sb.append("- ").append(nz(m.getMetricName()));
                if (notBlank(m.getMetricCode())) sb.append("[").append(m.getMetricCode()).append("]");
                if (notBlank(m.getCalcFormula())) sb.append(" 口径=").append(cap(m.getCalcFormula(), 160));
                if (notBlank(m.getDimensions())) sb.append(" 维度=").append(cap(m.getDimensions(), 80));
                if (notBlank(m.getUnit())) sb.append(" 单位=").append(m.getUnit());
                if (notBlank(m.getCategory())) sb.append(" 分类=").append(m.getCategory());
                sb.append('\n');
                if (sb.length() > 6000) break;
            }
            return sb.toString();
        } catch (Exception e) { return "(指标读取失败)"; }
    }

    private String domainMaterial(String domainName, List<DnTableMeta> tables) {
        StringBuilder sb = new StringBuilder();
        if (tables == null) return sb.toString();
        tables.sort((a, b) -> {
            long ra = a.getRowCount() == null ? 0 : a.getRowCount();
            long rb = b.getRowCount() == null ? 0 : b.getRowCount();
            return Long.compare(rb, ra);
        });
        // 批量取前 COLS_TABLES 张表的字段(一次 IN 查询, 避免 N+1), 按 table_meta_id 分组
        Map<Long, List<DnColumnMeta>> colsByTable = new LinkedHashMap<>();
        List<Long> colTableIds = new ArrayList<>();
        for (DnTableMeta t : tables) { if (t.getId() != null && colTableIds.size() < COLS_TABLES) colTableIds.add(t.getId()); }
        if (!colTableIds.isEmpty()) {
            try {
                List<DnColumnMeta> allCols = columnMetaMapper.selectList(new QueryWrapper<DnColumnMeta>()
                        .in("table_meta_id", colTableIds).orderByAsc("ordinal").last("LIMIT " + (45 * COLS_TABLES)));
                for (DnColumnMeta c : allCols) colsByTable.computeIfAbsent(c.getTableMetaId(), k -> new ArrayList<>()).add(c);
            } catch (Exception ignore) {}
        }
        int n = 0;
        for (DnTableMeta t : tables) {
            if (n >= TABLES_PER_DOMAIN) break;
            sb.append("- ").append(nz(t.getDatabaseName())).append(".").append(nz(t.getTableName()));
            if (notBlank(t.getTableComment())) sb.append(" (").append(cap(t.getTableComment(), 60)).append(")");
            sb.append('\n');
            // 关键字段(有业务名/描述的; 来自预加载批量结果)
            List<DnColumnMeta> cols = t.getId() == null ? null : colsByTable.get(t.getId());
            if (cols != null) {
                StringBuilder cb = new StringBuilder();
                int cc = 0;
                for (DnColumnMeta c : cols) {
                    if (notBlank(c.getBusinessName()) || notBlank(c.getBusinessDesc())) {
                        cb.append("    · ").append(nz(c.getColumnName()));
                        if (notBlank(c.getBusinessName())) cb.append("=").append(c.getBusinessName());
                        if (notBlank(c.getBusinessDesc())) cb.append("(").append(cap(c.getBusinessDesc(), 40)).append(")");
                        cb.append('\n');
                        if (++cc >= 12) break;
                    }
                }
                if (cb.length() > 0) sb.append(cb);
            }
            n++;
            if (sb.length() > 5000) break;
        }
        return sb.toString();
    }

    private String domainSopMaterial(String domain) {
        try {
            List<DnAiIndustrySop> sops = sopMapper.selectList(new QueryWrapper<DnAiIndustrySop>()
                    .eq("status", "active").and(w -> w.eq("domain", domain).or().eq("domain", "global"))
                    .orderByDesc("updated_at").last("LIMIT 10"));
            if (sops == null || sops.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (DnAiIndustrySop s : sops) {
                sb.append("- [").append(typeLabel(s.getSopType())).append("] ").append(nz(s.getTitle()))
                  .append(": ").append(cap(s.getContent(), 200)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // ============ helpers ============
    private void upsertProfile(String key, String content) {
        DnAiProjectProfile ex = profileMapper.selectOne(new QueryWrapper<DnAiProjectProfile>()
                .eq("profile_key", key).last("LIMIT 1"));
        if (ex == null) {
            DnAiProjectProfile p = new DnAiProjectProfile();
            p.setProfileKey(key); p.setContent(content);
            p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
            try { profileMapper.insert(p); } catch (Exception e) {
                profileMapper.update(null, new UpdateWrapper<DnAiProjectProfile>().eq("profile_key", key)
                        .set("content", content).set("updated_at", LocalDateTime.now()));
            }
        } else {
            profileMapper.update(null, new UpdateWrapper<DnAiProjectProfile>().eq("profile_key", key)
                    .set("content", content).set("updated_at", LocalDateTime.now()));
        }
    }

    private String distill(String prompt) {
        try {
            String raw = aiAssistService.chat(prompt, "");
            if (raw == null) return null;
            if (raw.startsWith("AI 功能未配置") || raw.startsWith("AI 请求失败") || raw.equals("AI 返回格式异常")) return null;
            String clean = AgentTextUtil.redactSecrets(AgentTextUtil.sanitize(raw)).trim();
            return clean.isEmpty() ? null : clean;
        } catch (Exception e) { return null; }
    }

    private static String typeLabel(String t) {
        if (t == null) return "经验";
        switch (t) {
            case "flow": return "业务流程";
            case "report": return "报表开发";
            case "caliber": return "指标口径";
            case "pitfall": return "坑/注意";
            case "glossary": return "术语";
            default: return "经验";
        }
    }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String cap(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
