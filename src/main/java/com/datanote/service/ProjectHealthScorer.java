package com.datanote.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目健康分（纯函数，可单测）。五维各 0-20，合计 0-100：
 * 资产 / 成员 / 发布 / 运行 / 活跃。即时计算，无新表。
 */
public final class ProjectHealthScorer {
    private ProjectHealthScorer() {}

    public static Map<String, Object> score(long assetTotal, long memberCount, long releaseTotal,
                                            long jobSuccess, long jobFailed, long activityCount) {
        int asset = (int) Math.min(20, Math.max(0, assetTotal * 4));
        int member = memberCount >= 3 ? 20 : (memberCount == 2 ? 14 : (memberCount == 1 ? 8 : 0));
        int release = (int) Math.min(20, Math.max(0, releaseTotal * 7));
        long runTotal = jobSuccess + jobFailed;
        int run = runTotal == 0 ? 12 : (int) Math.round(20.0 * Math.max(0, jobSuccess) / runTotal);
        int active = (int) Math.min(20, Math.max(0, activityCount * 2));
        int total = asset + member + release + run + active;

        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("asset", asset);
        dims.put("member", member);
        dims.put("release", release);
        dims.put("run", run);
        dims.put("active", active);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("level", total >= 80 ? "优秀" : (total >= 60 ? "良好" : (total >= 40 ? "一般" : "待完善")));
        r.put("dims", dims);
        return r;
    }
}
