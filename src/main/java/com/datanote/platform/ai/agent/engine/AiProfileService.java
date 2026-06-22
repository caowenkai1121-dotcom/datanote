package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiMemorySkillMapper;
import com.datanote.platform.ai.agent.mapper.DnAiProjectProfileMapper;
import com.datanote.platform.ai.agent.mapper.DnAiUserProfileMapper;
import com.datanote.platform.ai.agent.model.DnAiMemorySkill;
import com.datanote.platform.ai.agent.model.DnAiProjectProfile;
import com.datanote.platform.ai.agent.model.DnAiUserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 长久记忆/画像服务(天工开物·格物致知): 从沉淀经验蒸馏 用户画像(隔离)+项目画像(全局), 注入 agent 上下文;
 * 每日汇总精简(务实最小够用): 蒸馏画像 + 裁剪原始经验防膨胀。降级: AI 不可用则跳过, 不阻塞主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProfileService {

    private final DnAiUserProfileMapper userProfileMapper;
    private final DnAiProjectProfileMapper projectProfileMapper;
    private final DnAiMemorySkillMapper memoryMapper;
    private final com.datanote.platform.ai.agent.mapper.DnAiApprovalMapper approvalMapper;
    private final AiAssistService aiAssistService;

    public static final String GLOBAL = "global";
    private static final int KEEP_PER_OWNER = 80;     // 每用户保留活跃经验上限, 余裁为 archived
    private static final int USER_DISTILL_TOPN = 60;  // 蒸馏用户画像取的近期经验条数
    private static final int PROJ_DISTILL_TOPN = 120;  // 蒸馏项目画像取的近期经验条数
    private static final int USER_CAP = 1200;          // 用户画像正文上限
    private static final int PROJ_CAP = 1800;          // 项目画像正文上限

    // ===== 读取(注入 prompt / 抽屉展示) =====
    public DnAiUserProfile getUserProfile(String userName) {
        if (userName == null) return null;
        return userProfileMapper.selectOne(new QueryWrapper<DnAiUserProfile>().eq("user_name", userName).last("LIMIT 1"));
    }
    public DnAiProjectProfile getProjectProfile() {
        return projectProfileMapper.selectOne(new QueryWrapper<DnAiProjectProfile>().eq("profile_key", GLOBAL).last("LIMIT 1"));
    }
    public String userProfileText(String userName) {
        DnAiUserProfile p = getUserProfile(userName);
        return p == null ? null : trimToNull(p.getContent());
    }
    public String projectProfileText() {
        DnAiProjectProfile p = getProjectProfile();
        return p == null ? null : trimToNull(p.getContent());
    }

    /** 手动异步触发汇总(运维/测试用, 免等每日 tick)。 */
    @org.springframework.scheduling.annotation.Async("aiDigestExecutor")
    public void runDailyDigestAsync() { runDailyDigest(); }

    // ===== 每日汇总: 蒸馏画像 + 裁剪原始经验 =====
    public void runDailyDigest() {
        if (!aiAssistService.isAvailable()) { log.info("[profile] AI 未配置, 跳过每日汇总"); return; }
        long t0 = System.currentTimeMillis();
        try { digestProjectProfile(); } catch (Exception e) { log.warn("[profile] 项目画像蒸馏失败: {}", e.getMessage()); }
        // 近 14 天有沉淀的用户逐个蒸馏画像
        LocalDateTime cutoff = LocalDateTime.now().minusDays(14);
        List<String> owners = new ArrayList<>();
        try {
            List<DnAiMemorySkill> ds = memoryMapper.selectList(new QueryWrapper<DnAiMemorySkill>()
                    .select("DISTINCT owner").eq("status", "active").isNotNull("owner")
                    .ge("updated_at", cutoff).last("LIMIT 200")); // 封顶每日蒸馏用户数, 防大规模时 LLM 成本失控
            for (DnAiMemorySkill m : ds) if (m.getOwner() != null && !"anonymous".equals(m.getOwner())) owners.add(m.getOwner());
        } catch (Exception e) { log.warn("[profile] 取活跃用户失败: {}", e.getMessage()); }
        for (String owner : owners) {
            try { digestUserProfile(owner); } catch (Exception e) { log.warn("[profile] 用户画像蒸馏失败 {}: {}", owner, e.getMessage()); }
            try { pruneOwner(owner); } catch (Exception e) { log.warn("[profile] 裁剪经验失败 {}: {}", owner, e.getMessage()); }
        }
        try { pruneGlobalStale(); } catch (Exception e) { log.warn("[profile] 全局裁剪失败: {}", e.getMessage()); }
        try { pruneStaleApprovals(); } catch (Exception e) { log.warn("[profile] 清理过期审批失败: {}", e.getMessage()); }
        log.info("[profile] 每日汇总完成: {} 个用户, 耗时 {}ms", owners.size(), System.currentTimeMillis() - t0);
    }

    private void digestUserProfile(String owner) {
        List<DnAiMemorySkill> recent = memoryMapper.selectList(new QueryWrapper<DnAiMemorySkill>()
                .eq("status", "active").eq("owner", owner)
                .orderByDesc("updated_at").last("LIMIT " + USER_DISTILL_TOPN));
        if (recent == null || recent.isEmpty()) return;
        DnAiUserProfile old = getUserProfile(owner);
        String prompt = "你在为数据开发平台用户构建【长期画像】。基于【既有画像】与【近期沉淀的经验/操作】, 蒸馏更新为一份简明中文画像(≤" + USER_CAP + "字), 覆盖: "
                + "角色与专长、常用操作/工具、关注的数据域与库表、操作风格与偏好、常见任务模式。合并去重, 保留稳定特征, 去掉一次性琐碎与失败噪声。"
                + "绝不包含任何密钥/口令/凭据。只输出画像正文, 不要前后缀。\n\n【既有画像】\n" + (old == null ? "(无)" : cap(old.getContent(), USER_CAP))
                + "\n\n【近期经验】\n" + memText(recent, 4000);
        String distilled = distill(prompt);
        if (distilled == null) return;
        upsertUserProfile(owner, cap(distilled, USER_CAP));
    }

    private void digestProjectProfile() {
        List<DnAiMemorySkill> recent = memoryMapper.selectList(new QueryWrapper<DnAiMemorySkill>()
                .eq("status", "active").orderByDesc("updated_at").last("LIMIT " + PROJ_DISTILL_TOPN));
        if (recent == null || recent.isEmpty()) return;
        DnAiProjectProfile old = getProjectProfile();
        String prompt = "你在为一个数据开发平台项目构建【全局画像】(所有用户共享)。基于【既有项目画像】与【近期全局沉淀经验】, 蒸馏更新为简明中文画像(≤" + PROJ_CAP + "字), 覆盖: "
                + "主要数据域与常用库表、命名规范、常见处理流程(如本项目 ODS→DWD→DWS→ADS 的惯例)、高频任务类型、已知坑与最佳实践。合并去重, 保留方法论, 去掉个人化与一次性细节。"
                + "绝不包含任何密钥/口令/凭据。只输出画像正文。\n\n【既有项目画像】\n" + (old == null ? "(无)" : cap(old.getContent(), PROJ_CAP))
                + "\n\n【近期全局经验】\n" + memText(recent, 6000);
        String distilled = distill(prompt);
        if (distilled == null) return;
        upsertProjectProfile(cap(distilled, PROJ_CAP));
    }

    /** 每用户活跃经验按 命中+近因 排序, 超过上限的裁为 archived(防膨胀)。 */
    private void pruneOwner(String owner) {
        List<DnAiMemorySkill> all = memoryMapper.selectList(new QueryWrapper<DnAiMemorySkill>()
                .eq("status", "active").eq("owner", owner)
                .orderByDesc("hit_count").orderByDesc("updated_at").orderByDesc("id")); // id 兜底保排序确定
        if (all == null || all.size() <= KEEP_PER_OWNER) return;
        List<Long> archiveIds = new ArrayList<>();
        for (int i = KEEP_PER_OWNER; i < all.size(); i++) archiveIds.add(all.get(i).getId());
        if (!archiveIds.isEmpty()) {
            memoryMapper.update(null, new UpdateWrapper<DnAiMemorySkill>()
                    .in("id", archiveIds).set("status", "archived").set("updated_at", LocalDateTime.now()));
            log.info("[profile] 裁剪 {} 条经验(用户 {}, 已蒸馏入画像)", archiveIds.size(), owner);
        }
    }

    /** 清理僵尸审批: 超 7 天仍 pending 的写审批自动转 rejected(防无人处理长期堆积占列表)。 */
    private void pruneStaleApprovals() {
        approvalMapper.update(null, new UpdateWrapper<com.datanote.platform.ai.agent.model.DnAiApproval>()
                .eq("status", "pending").lt("created_at", LocalDateTime.now().minusDays(7))
                .set("status", "rejected").set("decided_by", "auto-expired").set("decided_at", LocalDateTime.now()));
    }

    /** 全局裁剪: 30 天未更新且命中<2 的活跃经验归档(降噪)。 */
    private void pruneGlobalStale() {
        memoryMapper.update(null, new UpdateWrapper<DnAiMemorySkill>()
                .eq("status", "active").lt("updated_at", LocalDateTime.now().minusDays(30)).lt("hit_count", 2)
                .set("status", "archived").set("updated_at", LocalDateTime.now()));
    }

    private void upsertUserProfile(String owner, String content) {
        DnAiUserProfile ex = getUserProfile(owner);
        if (ex == null) {
            DnAiUserProfile p = new DnAiUserProfile();
            p.setUserName(owner); p.setContent(content);
            p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
            try { userProfileMapper.insert(p); } catch (Exception e) { // 并发唯一键: 转更新
                userProfileMapper.update(null, new UpdateWrapper<DnAiUserProfile>().eq("user_name", owner)
                        .set("content", content).set("updated_at", LocalDateTime.now()));
            }
        } else {
            userProfileMapper.update(null, new UpdateWrapper<DnAiUserProfile>().eq("user_name", owner)
                    .set("content", content).set("updated_at", LocalDateTime.now()));
        }
    }

    private void upsertProjectProfile(String content) {
        DnAiProjectProfile ex = getProjectProfile();
        if (ex == null) {
            DnAiProjectProfile p = new DnAiProjectProfile();
            p.setProfileKey(GLOBAL); p.setContent(content);
            p.setCreatedAt(LocalDateTime.now()); p.setUpdatedAt(LocalDateTime.now());
            try { projectProfileMapper.insert(p); } catch (Exception e) {
                projectProfileMapper.update(null, new UpdateWrapper<DnAiProjectProfile>().eq("profile_key", GLOBAL)
                        .set("content", content).set("updated_at", LocalDateTime.now()));
            }
        } else {
            projectProfileMapper.update(null, new UpdateWrapper<DnAiProjectProfile>().eq("profile_key", GLOBAL)
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

    private static String memText(List<DnAiMemorySkill> ms, int cap) {
        StringBuilder sb = new StringBuilder();
        for (DnAiMemorySkill m : ms) {
            if (m == null) continue;
            sb.append("- [").append(m.getType()).append("] ").append(nz(m.getTitle()));
            if (m.getContent() != null) sb.append(": ").append(cap(m.getContent(), 240));
            sb.append('\n');
            if (sb.length() > cap) break;
        }
        return sb.toString();
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String cap(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
    private static String trimToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s.trim(); }
}
