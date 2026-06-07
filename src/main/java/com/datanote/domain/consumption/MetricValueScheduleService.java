package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 指标值定时计算 —— 每日定时计算全部启用指标的当前值并落快照，持续积累指标值时序，供消费层看板/趋势消费。
 * 复用 {@link MetricValueService#calc}；单条失败不影响其余（calc 内部已兜底落 error 快照）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricValueScheduleService {

    private final DnMetricMapper metricMapper;
    private final MetricValueService valueService;

    /** 每日 01:30 计算全部启用指标值 */
    @Scheduled(cron = "0 30 1 * * ?")
    public void dailyCalc() {
        List<DnMetric> metrics;
        try {
            metrics = metricMapper.selectList(new QueryWrapper<DnMetric>().eq("status", 1));
        } catch (Exception e) {
            log.error("指标定时计算：扫描启用指标失败", e);
            return;
        }
        int ok = 0, fail = 0;
        for (DnMetric m : metrics) {
            try {
                DnMetricValue v = valueService.calc(m.getId(), "schedule");
                if ("success".equals(v.getRunStatus())) ok++; else fail++;
            } catch (Exception e) {
                fail++;
                log.warn("指标定时计算异常 metricId={}: {}", m.getId(), e.getMessage());
            }
        }
        log.info("指标定时计算完成：成功 {} / 失败 {} / 共 {}", ok, fail, metrics.size());
    }
}
