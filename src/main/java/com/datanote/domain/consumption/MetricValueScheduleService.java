package com.datanote.domain.consumption;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 指标值定时计算 —— 每日定时计算全部启用指标的当前值并落快照，持续积累指标值时序，供消费层看板/趋势消费。
 * 复用 {@link MetricValueService#calc}；单条失败不影响其余（calc 内部已兜底落 error 快照）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricValueScheduleService {

    private final MetricValueService valueService;

    /** 每日 02:10 计算全部启用指标值(错峰: 避开 01:30 健康分快照与 01:00 元数据采集); T+1 口径显式传昨日 */
    @Scheduled(cron = "0 10 2 * * ?")
    public void dailyCalc() {
        try {
            java.util.Map<String, Object> r = valueService.calcAllEnabled("schedule", java.time.LocalDate.now().minusDays(1));
            log.info("指标定时计算完成：{}", r);
        } catch (Exception e) {
            log.error("指标定时计算失败", e);
        }
    }
}
