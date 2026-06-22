package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiProjectProfileMapper;
import com.datanote.platform.ai.agent.model.DnAiProjectProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日汇总调度(天工开物·务实最小够用): 每天一次蒸馏 用户/项目画像 + 裁剪原始经验防膨胀。
 * 以 dn_ai_project_profile 的 '__digest_date__' 行作日期 marker, CAS 占行保证每自然日只跑一次(多实例/多线程安全)。
 * 异步执行不阻塞调度线程; AI 不可用则 AiProfileService 内部跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyDigestScheduler {

    private final DnAiProjectProfileMapper projectProfileMapper;
    private final AiProfileService profileService;

    @javax.annotation.Resource(name = "aiLearnExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;

    private static final String MARK = "__digest_date__";

    @Scheduled(fixedDelay = 1800_000L) // 每 30min 检查一次; 跨自然日才真正执行
    public void tick() {
        String today = LocalDate.now().toString();
        DnAiProjectProfile mark;
        try {
            mark = projectProfileMapper.selectOne(new QueryWrapper<DnAiProjectProfile>().eq("profile_key", MARK).last("LIMIT 1"));
        } catch (Exception e) { log.warn("[digest] 读 marker 失败: {}", e.getMessage()); return; }
        if (mark != null && today.equals(mark.getContent())) return; // 今天已汇总

        boolean won;
        if (mark == null) {
            DnAiProjectProfile m = new DnAiProjectProfile();
            m.setProfileKey(MARK); m.setContent(today);
            m.setCreatedAt(LocalDateTime.now()); m.setUpdatedAt(LocalDateTime.now());
            try { projectProfileMapper.insert(m); won = true; }
            catch (Exception dup) { won = false; } // 并发唯一键: 别人抢到了
        } else {
            // CAS: 仅当 content 仍为旧值时改成今天, 抢到才执行
            int rows = projectProfileMapper.update(null, new UpdateWrapper<DnAiProjectProfile>()
                    .eq("profile_key", MARK).eq("content", mark.getContent())
                    .set("content", today).set("updated_at", LocalDateTime.now()));
            won = rows > 0;
        }
        if (!won) return;

        try {
            executor.execute(() -> {
                try { profileService.runDailyDigest(); }
                catch (Exception e) { log.warn("[digest] 每日汇总执行异常", e); }
            });
        } catch (Exception rejected) {
            log.warn("[digest] 执行器忙, 跳过本次(下次再领): {}", rejected.getMessage());
        }
    }
}
