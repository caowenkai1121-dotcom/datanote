package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectTaskMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * N7: 项目健康分计算下沉(原 ProjectController.health 内联逻辑)。
 * 数据采集复用 ProjectOverviewService.overview 聚合(jobRuns/scriptRuns/qualityRuns 三段), 消重复查询:
 *  - run    口径=B8 最近终态(termSuccess/termFailed), 同步任务+脚本合并(数据开发型项目不失真)
 *  - quality口径=B5 仅 success 运行的平均通过率(avgPassPct), 全无可评估→null 中性
 *  - task   超期=dueDate<今天 且 status!=DONE
 *  - collab 口径=B9 近30天合成活动数(僵尸项目不再恒满分)
 */
@Service
@RequiredArgsConstructor
public class ProjectHealthService {

    private final ProjectService projectService;
    private final ProjectOverviewService overviewService;
    private final DnProjectTaskMapper taskMapper;

    public Map<String, Object> score(Long projectId) {
        Map<String, Object> ov = overviewService.overview(projectId);
        long memberCount = num(ov.get("memberCount"));
        long releasePending = num(ov.get("releasePending"));

        // B9: 近30天活动数(合成事件带 ISO 时间)
        long actCnt = 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        Object acts = ov.get("activity");
        if (acts instanceof List) {
            for (Object o : (List<?>) acts) {
                if (!(o instanceof Map)) continue;
                Object at = ((Map<?, ?>) o).get("at");
                if (at == null) continue;
                try {
                    if (LocalDateTime.parse(String.valueOf(at)).isAfter(cutoff)) actCnt++;
                } catch (Exception ignore) {}
            }
        }

        // B8+N8: run 维=同步任务+脚本 最近终态合并
        long termSuccess = 0, termFailed = 0;
        for (String key : new String[]{"jobRuns", "scriptRuns"}) {
            Object seg = ov.get(key);
            if (seg instanceof Map) {
                termSuccess += num(((Map<?, ?>) seg).get("termSuccess"));
                termFailed += num(((Map<?, ?>) seg).get("termFailed"));
            }
        }

        // B5: 质量维=success 运行平均通过率; P4: 绑定指标时与"指标取值健康率"各半合并(无指标项目零漂移)
        Integer rulePct = null, metricPct = null;
        Object qr = ov.get("qualityRuns");
        if (qr instanceof Map) {
            Object avg = ((Map<?, ?>) qr).get("avgPassPct");
            if (avg instanceof Number) rulePct = ((Number) avg).intValue();
        }
        Object mr = ov.get("metricRuns");
        if (mr instanceof Map) {
            Object pct = ((Map<?, ?>) mr).get("okPct");
            if (pct instanceof Number) metricPct = ((Number) pct).intValue();
        }
        // 注意用 if-else 而非三元: 混合 Integer/int 分支的三元会整体推断为 int, null 拆箱 NPE
        Integer qualityPassPct;
        if (rulePct == null) qualityPassPct = metricPct;
        else if (metricPct == null) qualityPassPct = rulePct;
        else qualityPassPct = (int) Math.round((rulePct + metricPct) / 2.0);

        // 任务执行力
        LocalDate today = LocalDate.now();
        List<DnProjectTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<DnProjectTask>()
                .eq(DnProjectTask::getProjectId, projectId));
        if (tasks == null) tasks = new ArrayList<>();
        long taskTotal = tasks.size(), taskOverdue = 0;
        for (DnProjectTask t : tasks) {
            if (t != null && t.getDueDate() != null && !"DONE".equals(t.getStatus()) && t.getDueDate().isBefore(today)) taskOverdue++;
        }

        return ProjectHealthScorer.score(termSuccess, termFailed, qualityPassPct,
                taskTotal, taskOverdue, releasePending, memberCount, actCnt);
    }

    /**
     * N7: 批量打分(项目列表/工作台徽标)。仅活跃项目, 数量有限直接循环;
     * 60 秒进程内缓存——徽标无强实时要求, 防列表页反复进出全量拉执行历史。
     */
    private volatile Map<Long, Map<String, Object>> _batchCache;
    private volatile long _batchCacheAt;

    public Map<Long, Map<String, Object>> scoreBatch() {
        Map<Long, Map<String, Object>> cached = _batchCache;
        if (cached != null && System.currentTimeMillis() - _batchCacheAt < 60_000L) return cached;
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (DnProject p : projectService.list("ACTIVE")) {
            if (p == null || p.getId() == null) continue;
            try { out.put(p.getId(), score(p.getId())); } catch (Exception ignore) {}
        }
        _batchCache = out;
        _batchCacheAt = System.currentTimeMillis();
        return out;
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
}
