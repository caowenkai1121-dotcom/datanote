package com.datanote.domain.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目健康分（纯函数，可单测）。
 * P1 重写：从"配置完整度"改为【运行驱动】——分数反映项目真实运行状态，可行动:
 *   run     0-30  绑定同步任务近况成功率（无运行给中性 15，不白送满分）
 *   quality 0-25  绑定质量规则最近一次执行的平均通过率（无规则给中性 12）
 *   task    0-20  任务执行力 = 1 - 超期未完成占比（无任务给中性 10）
 *   release 0-15  发布管控：待审批积压每单扣 5
 *   collab  0-10  协作：成员数 + 近期活动
 */
public final class ProjectHealthScorer {
    private ProjectHealthScorer() {}

    /**
     * @param jobSuccess / jobFailed 绑定同步任务近况成功/失败次数
     * @param qualityPassPct 绑定质量规则最近执行平均通过率 0-100；null=无可评估规则
     * @param taskTotal / taskOverdue 项目任务总数 / 超期未完成数
     * @param releasePending 待审批发布单数
     * @param memberCount 成员数
     * @param activityCount 近期活动条数
     */
    public static Map<String, Object> score(long jobSuccess, long jobFailed, Integer qualityPassPct,
                                            long taskTotal, long taskOverdue,
                                            long releasePending, long memberCount, long activityCount) {
        long runTotal = jobSuccess + jobFailed;
        int run = runTotal == 0 ? 15 : (int) Math.round(30.0 * Math.max(0, jobSuccess) / runTotal);
        int quality = qualityPassPct == null ? 12 : (int) Math.round(25.0 * Math.min(100, Math.max(0, qualityPassPct)) / 100);
        int task = taskTotal == 0 ? 10 : (int) Math.round(20.0 * (1.0 - (double) Math.min(taskTotal, Math.max(0, taskOverdue)) / taskTotal));
        int release = (int) Math.max(0, 15 - Math.max(0, releasePending) * 5);
        int collab = (memberCount >= 2 ? 5 : 0) + (activityCount > 0 ? 5 : 0);
        int total = run + quality + task + release + collab;

        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("run", run);
        dims.put("quality", quality);
        dims.put("task", task);
        dims.put("release", release);
        dims.put("collab", collab);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("level", total >= 80 ? "优秀" : (total >= 60 ? "良好" : (total >= 40 ? "一般" : "待完善")));
        r.put("dims", dims);
        // 维度满分表(前端画条用)
        Map<String, Object> max = new LinkedHashMap<>();
        max.put("run", 30); max.put("quality", 25); max.put("task", 20); max.put("release", 15); max.put("collab", 10);
        r.put("dimMax", max);
        return r;
    }
}
