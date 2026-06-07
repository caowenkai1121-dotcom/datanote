package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnQualityRuleMapper;
import com.datanote.model.DnQualityRule;
import com.datanote.model.DnQualityRun;
import com.datanote.sync.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量规则调度 — 接通死字段 schedule_cron，按分钟扫描启用规则并自动执行；
 * 失败或低于阈值时复用 AlertService 告警（未启用时静默，零副作用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityScheduleService {

    private final DnQualityRuleMapper ruleMapper;
    private final QualityService qualityService;
    private final AlertService alertService;

    /**
     * 每分钟扫描一次：启用且 cron 命中本分钟的规则触发执行。
     * 用 fixedDelay 避免上次扫描未结束又重入。
     */
    @Scheduled(fixedDelay = 60000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<DnQualityRule> qw = new QueryWrapper<>();
        qw.eq("status", 1).isNotNull("schedule_cron").ne("schedule_cron", "");
        List<DnQualityRule> rules;
        try {
            rules = ruleMapper.selectList(qw);
        } catch (Exception e) {
            log.error("质量调度扫描规则失败", e);
            return;
        }
        for (DnQualityRule rule : rules) {
            if (!isDueThisMinute(rule.getScheduleCron(), now)) {
                continue;
            }
            try {
                DnQualityRun run = qualityService.executeRule(rule);
                handleResult(rule, run);
            } catch (Exception e) {
                log.error("质量调度执行规则异常 ruleId={}", rule.getId(), e);
            }
        }
    }

    /**
     * 处理执行结果：error 或 failed（低于阈值）时告警。
     */
    private void handleResult(DnQualityRule rule, DnQualityRun run) {
        String status = run.getRunStatus();
        if ("error".equals(status)) {
            alert(rule, "quality_error", "执行异常: " + safe(run.getErrorMsg()));
        } else if ("failed".equals(status)) {
            String rate = run.getPassRate() != null ? run.getPassRate().toPlainString() : "?";
            String th = rule.getPassThreshold() != null ? rule.getPassThreshold().toPlainString() : "100";
            String block = isStrong(rule) ? "[强规则]" : "";
            alert(rule, "quality_failed", block + "通过率 " + rate + "% 低于阈值 " + th + "%");
        }
    }

    /** 告警 hook：复用同步告警通道（未配置 datanote.alert.enabled=true 时内部静默）。 */
    private void alert(DnQualityRule rule, String type, String message) {
        log.warn("质量告警 ruleId={} {} {}", rule.getId(), type, message);
        alertService.alert(rule.getId(), rule.getRuleName(), type, message);
    }

    /** 强规则：block_downstream=1，失败应阻塞下游（本期先标识，闭环阻塞由后续里程碑消费）。 */
    static boolean isStrong(DnQualityRule rule) {
        return rule.getBlockDownstream() != null && rule.getBlockDownstream() == 1;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * cron 在 [本分钟起点, 下一分钟起点) 区间内有触发点则该跑。
     * 空/null/非法 cron 一律不跑（避免错误表达式每分钟风暴）。
     */
    static boolean isDueThisMinute(String cron, LocalDateTime now) {
        if (cron == null || cron.trim().isEmpty()) {
            return false;
        }
        try {
            CronExpression expr = CronExpression.parse(cron.trim());
            LocalDateTime minuteStart = now.withSecond(0).withNano(0);
            LocalDateTime next = expr.next(minuteStart.minusSeconds(1));
            return next != null && next.isBefore(minuteStart.plusMinutes(1)) && !next.isBefore(minuteStart);
        } catch (IllegalArgumentException e) {
            log.warn("无法解析质量规则 cron '{}'，跳过", cron);
            return false;
        }
    }
}
