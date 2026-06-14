package com.datanote.domain.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.domain.orchestration.mapper.DnBaselineMapper;
import com.datanote.domain.orchestration.mapper.DnBaselineTaskMapper;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.orchestration.model.DnBaseline;
import com.datanote.domain.orchestration.model.DnBaselineTask;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.platform.notify.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基线做实(批4#14) —— 基线从摆设变 SLA 监控:
 * 启用基线的关联任务, 当日调度(run_date=T-1, run_type=daily)须在承诺时间(commitTime)前全部成功;
 * 过点未达 → 判打破(broken), 通知基线创建人(回退 admin), 同基线同日只发一次。
 * 状态口径: met=全部成功 / broken=已过点且有任务未成功 / pending=未到点 / empty=无关联任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselineCheckService {

    private final DnBaselineMapper baselineMapper;
    private final DnBaselineTaskMapper baselineTaskMapper;
    private final DnSchedulerRunMapper runMapper;
    private final NotificationService notificationService;

    /** 打破通知去重: baselineId@runDate -> 已通知。内存即可, 重启重置最多重发一次。 */
    private final Map<String, Boolean> notified = new ConcurrentHashMap<>();

    /** 每 5 分钟巡检(错开整点高峰)。 */
    @Scheduled(initialDelay = 90_000, fixedDelay = 300_000)
    public void patrol() {
        try {
            List<Map<String, Object>> statuses = statusToday();
            for (Map<String, Object> st : statuses) {
                if (!"broken".equals(st.get("todayStatus"))) continue;
                String key = st.get("id") + "@" + st.get("runDate");
                if (notified.putIfAbsent(key, Boolean.TRUE) != null) continue;
                Object createdBy = st.get("createdBy");
                String createdByStr = String.valueOf(createdBy).trim();
                String receiver = createdBy != null && !createdByStr.isEmpty()
                        && !"default".equals(createdBy) ? createdByStr : "admin";
                notificationService.notify(receiver, "BASELINE_BROKEN",
                        "基线打破: " + st.get("baselineName") + " 承诺 " + st.get("commitTime")
                                + " 前完成, 仍有 " + st.get("unmetCount") + " 个任务未成功",
                        "operations", (Long) st.get("id"), null);
                log.warn("基线打破 id={} name={} 未达成 {}", st.get("id"), st.get("baselineName"), st.get("unmetCount"));
            }
            // 防内存无限增长: 只保留当日 key
            String today = "@" + runDate();
            notified.keySet().removeIf(k -> !k.endsWith(today));
        } catch (Exception e) {
            log.warn("基线巡检失败", e);
        }
    }

    /** 全部启用基线的今日达成状况(供面板)。 */
    public List<Map<String, Object>> statusToday() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DnBaseline> baselines = baselineMapper.selectList(
                new QueryWrapper<DnBaseline>().eq("status", DnBaseline.STATUS_ENABLED));
        if (baselines == null) return out;
        LocalDate rd = runDate();
        LocalTime now = LocalTime.now();
        for (DnBaseline b : baselines) {
            if (b == null) continue;
            List<DnBaselineTask> tasks = baselineTaskMapper.selectList(
                    new QueryWrapper<DnBaselineTask>().eq("baseline_id", b.getId()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("baselineName", b.getBaselineName());
            m.put("commitTime", b.getCommitTime() != null ? b.getCommitTime().toString() : null);
            m.put("createdBy", b.getCreatedBy());
            m.put("runDate", rd.toString());
            List<Map<String, Object>> unmet = new ArrayList<>();
            if (tasks == null || tasks.isEmpty()) {
                m.put("todayStatus", "empty");
                m.put("unmetCount", 0);
                m.put("unmetTasks", unmet);
                out.add(m);
                continue;
            }
            for (DnBaselineTask t : tasks) {
                if (t == null) continue;
                QueryWrapper<DnSchedulerRun> qw = new QueryWrapper<>();
                qw.eq("task_id", t.getTaskId()).eq("task_type", t.getTaskType())
                        .eq("run_date", rd).eq("run_type", Constants.RUN_TYPE_DAILY)
                        .orderByDesc("id").last("LIMIT 1");
                DnSchedulerRun run = runMapper.selectOne(qw);
                int status = run == null || run.getStatus() == null ? Integer.MIN_VALUE : run.getStatus();
                if (status != DnSchedulerRun.STATUS_SUCCESS) {
                    Map<String, Object> u = new HashMap<>();
                    u.put("taskId", t.getTaskId());
                    u.put("taskType", t.getTaskType());
                    u.put("taskName", t.getTaskName());
                    u.put("runStatus", run == null ? "no_run" : String.valueOf(run.getStatus()));
                    unmet.add(u);
                }
            }
            boolean pastCommit = b.getCommitTime() == null || !now.isBefore(b.getCommitTime());
            String st = unmet.isEmpty() ? "met" : (pastCommit ? "broken" : "pending");
            m.put("todayStatus", st);
            m.put("unmetCount", unmet.size());
            m.put("unmetTasks", unmet);
            out.add(m);
        }
        return out;
    }

    /** 基线监控的数据日期口径: 与每日调度一致, T-1。 */
    static LocalDate runDate() {
        return LocalDate.now().minusDays(1);
    }
}
