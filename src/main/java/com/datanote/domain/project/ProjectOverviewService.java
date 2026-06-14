package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectAssetMapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.mapper.DnProjectReleaseMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectAsset;
import com.datanote.domain.project.model.DnProjectMember;
import com.datanote.domain.project.model.DnProjectRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** 项目概览大盘：资产/成员/发布统计 + 合成活动流。 */
@Service
@RequiredArgsConstructor
public class ProjectOverviewService {

    private final ProjectService projectService;
    private final ProjectAssetService assetService;
    private final DnProjectMemberMapper memberMapper;
    private final DnProjectAssetMapper assetMapper;
    private final DnProjectReleaseMapper releaseMapper;
    private final com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper taskExecutionMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRunMapper qualityRunMapper;
    private final com.datanote.domain.consumption.mapper.DnMetricValueMapper metricValueMapper;

    public Map<String, Object> overview(Long projectId) {
        DnProject p = projectService.getById(projectId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("project", p);

        // 项目资产列表一次性查出, 后续计数/运行聚合/活动流共用, 消除单次 overview 内 3 次重复查询
        List<DnProjectAsset> projectAssets = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId));
        if (projectAssets == null) projectAssets = new ArrayList<>();

        Map<String, Long> counts = countsFromAssets(projectAssets);
        long assetTotal = 0;
        for (Long v : counts.values()) assetTotal += v;
        r.put("assetCounts", counts);
        r.put("assetTotal", assetTotal);

        Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId));
        r.put("memberCount", memberCount == null ? 0 : memberCount);

        List<DnProjectRelease> releases = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId));
        if (releases == null) releases = new ArrayList<>();   // selectList 理论可返回 null
        long pending = 0, released = 0;
        for (DnProjectRelease rel : releases) {
            if (rel == null) continue;
            if ("PENDING".equals(rel.getStatus())) pending++;
            else if ("RELEASED".equals(rel.getStatus())) released++;
        }
        r.put("releaseTotal", releases.size());
        r.put("releasePending", pending);
        r.put("releaseReleased", released);

        r.put("activity", buildActivity(projectId, releases, projectAssets));
        // N8: 绑定资产运行四段(同步任务/脚本/质量规则/指标); jobRuns 键保留兼容旧前端
        Map<String, Object> runs = boundAssetRuns(projectAssets);
        r.put("jobRuns", runs.get("jobRuns"));
        r.put("scriptRuns", runs.get("scriptRuns"));
        r.put("qualityRuns", runs.get("qualityRuns"));
        r.put("metricRuns", runs.get("metricRuns"));
        return r;
    }

    /** 从已查出的资产列表按类型计数(语义同 ProjectAssetService.countsByType: 全类型有序、0 兜底), 免 5 次 selectCount。 */
    private Map<String, Long> countsFromAssets(List<DnProjectAsset> assets) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : ProjectAssetService.TYPES) m.put(t, 0L);
        if (assets != null) for (DnProjectAsset a : assets) {
            if (a == null) continue;
            String t = a.getAssetType();
            if (t != null && m.containsKey(t)) m.put(t, m.get(t) + 1);
        }
        return m;
    }

    /**
     * N8: 绑定资产运行聚合(泛化)——同步任务/脚本走 dn_task_execution, 质量规则走 dn_quality_run。
     * B8: 每资产同时给"最近一次"(展示口径, 含 RUNNING)与"最近终态"(健康分口径, 仅 SUCCESS/FAILED)。
     * 健康分(ProjectHealthService)复用本聚合, 防重复查询。
     */
    public Map<String, Object> boundAssetRuns(Long projectId) {
        List<DnProjectAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId));
        if (assets == null) assets = new ArrayList<>();
        return boundAssetRuns(assets);
    }

    /** overview 内复用已查出的资产列表做运行聚合, 不再重复查库(行为同 boundAssetRuns(projectId))。 */
    private Map<String, Object> boundAssetRuns(List<DnProjectAsset> assets) {
        if (assets == null) assets = new ArrayList<>();
        List<DnProjectAsset> jobs = new ArrayList<>(), scripts = new ArrayList<>(), rules = new ArrayList<>(), metrics = new ArrayList<>();
        for (DnProjectAsset a : assets) {
            if (a == null || a.getAssetId() == null) continue;
            if ("SYNC_JOB".equals(a.getAssetType())) jobs.add(a);
            else if ("SCRIPT".equals(a.getAssetType())) scripts.add(a);
            else if ("QUALITY_RULE".equals(a.getAssetType())) rules.add(a);
            else if ("METRIC".equals(a.getAssetType())) metrics.add(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobRuns", execAgg(jobs, false));
        out.put("scriptRuns", execAgg(scripts, true));
        out.put("qualityRuns", qualityAgg(rules));
        out.put("metricRuns", metricAgg(metrics));
        return out;
    }

    /** 指标聚合(P4): 绑定指标各自最新取值快照, ok=success/bad=error/未取值; okPct 供健康分质量维合并 */
    private Map<String, Object> metricAgg(List<DnProjectAsset> metrics) {
        Set<Long> ids = new LinkedHashSet<>();
        for (DnProjectAsset a : metrics) ids.add(a.getAssetId());
        Map<Long, com.datanote.domain.consumption.model.DnMetricValue> latest = new HashMap<>();
        if (!ids.isEmpty()) {
            List<com.datanote.domain.consumption.model.DnMetricValue> rows = metricValueMapper.selectList(
                    new LambdaQueryWrapper<com.datanote.domain.consumption.model.DnMetricValue>()
                            .in(com.datanote.domain.consumption.model.DnMetricValue::getMetricId, ids)
                            .orderByDesc(com.datanote.domain.consumption.model.DnMetricValue::getId));
            if (rows != null) {
                for (com.datanote.domain.consumption.model.DnMetricValue v : rows) {
                    if (v != null && v.getMetricId() != null) latest.putIfAbsent(v.getMetricId(), v);
                }
            }
        }
        long ok = 0, bad = 0, neverRun = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DnProjectAsset a : metrics) {
            com.datanote.domain.consumption.model.DnMetricValue v = latest.get(a.getAssetId());
            if (v == null) { neverRun++; continue; }
            if ("success".equalsIgnoreCase(v.getRunStatus())) ok++; else bad++;
            if (recent.size() < 8) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
                m.put("name", a.getAssetName());
                m.put("status", "success".equalsIgnoreCase(v.getRunStatus()) ? "SUCCESS" : "FAILED");
                m.put("value", v.getMetricValue());
                recent.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", metrics.size());
        out.put("ok", ok);
        out.put("bad", bad);
        out.put("neverRun", neverRun);
        out.put("okPct", (ok + bad) > 0 ? (int) Math.round(ok * 100.0 / (ok + bad)) : null);
        out.put("recent", recent);
        return out;
    }

    /** dn_task_execution 聚合: isScript=false 按 syncTaskId+DbSync, true 按 scriptId+script */
    private Map<String, Object> execAgg(List<DnProjectAsset> assets, boolean isScript) {
        long success = 0, failed = 0, running = 0, neverRun = 0, other = 0, termSuccess = 0, termFailed = 0;
        List<Object[]> recents = new ArrayList<>(); // [time, name, status]
        Set<Long> ids = new LinkedHashSet<>();
        for (DnProjectAsset a : assets) ids.add(a.getAssetId());
        Map<Long, com.datanote.domain.orchestration.model.DnTaskExecution> latest = new HashMap<>();
        Map<Long, com.datanote.domain.orchestration.model.DnTaskExecution> latestTerminal = new HashMap<>();
        if (!ids.isEmpty()) {
            LambdaQueryWrapper<com.datanote.domain.orchestration.model.DnTaskExecution> qw =
                    new LambdaQueryWrapper<com.datanote.domain.orchestration.model.DnTaskExecution>()
                            .orderByDesc(com.datanote.domain.orchestration.model.DnTaskExecution::getId);
            if (isScript) qw.in(com.datanote.domain.orchestration.model.DnTaskExecution::getScriptId, ids)
                    .eq(com.datanote.domain.orchestration.model.DnTaskExecution::getTaskType, "script");
            else qw.in(com.datanote.domain.orchestration.model.DnTaskExecution::getSyncTaskId, ids)
                    .eq(com.datanote.domain.orchestration.model.DnTaskExecution::getTaskType, "DbSync");
            List<com.datanote.domain.orchestration.model.DnTaskExecution> execs = taskExecutionMapper.selectList(qw);
            if (execs != null) {
                for (com.datanote.domain.orchestration.model.DnTaskExecution e : execs) {
                    if (e == null) continue;
                    Long key = isScript ? e.getScriptId() : e.getSyncTaskId();
                    if (key == null) continue;
                    latest.putIfAbsent(key, e); // id 倒序, 首次出现即最新一条
                    if ("SUCCESS".equals(e.getStatus()) || "FAILED".equals(e.getStatus())) latestTerminal.putIfAbsent(key, e);
                }
            }
        }
        for (DnProjectAsset a : assets) {
            com.datanote.domain.orchestration.model.DnTaskExecution e = latest.get(a.getAssetId());
            if (e == null) { neverRun++; continue; }
            String st = e.getStatus();
            if ("SUCCESS".equals(st)) success++;
            else if ("FAILED".equals(st)) failed++;
            else if ("RUNNING".equals(st)) running++;
            else other++; // STOPPED 等其它状态，保证计数与 total 自洽
            recents.add(new Object[]{e.getStartTime(), a.getAssetName(), st});
            com.datanote.domain.orchestration.model.DnTaskExecution te = latestTerminal.get(a.getAssetId());
            if (te != null) {
                if ("SUCCESS".equals(te.getStatus())) termSuccess++;
                else termFailed++;
            }
        }
        recents.sort((x, y) -> {
            LocalDateTime a = (LocalDateTime) x[0], b = (LocalDateTime) y[0];
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return b.compareTo(a);
        });
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 0; i < recents.size() && i < 8; i++) {
            Object[] e = recents.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e[0] == null ? null : e[0].toString());
            m.put("name", e[1]);
            m.put("status", e[2]);
            recent.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", assets.size());
        out.put("success", success);
        out.put("failed", failed);
        out.put("running", running);
        out.put("other", other);
        out.put("neverRun", neverRun);
        out.put("termSuccess", termSuccess);   // B8: 健康分 run 维口径(最近终态)
        out.put("termFailed", termFailed);
        out.put("recent", recent);
        return out;
    }

    /** 质量规则聚合: rule_id in 批量取各规则最新一条与最新 success 一条(B5: 通过率仅取 success 口径) */
    private Map<String, Object> qualityAgg(List<DnProjectAsset> rules) {
        Set<Long> ids = new LinkedHashSet<>();
        for (DnProjectAsset a : rules) ids.add(a.getAssetId());
        Map<Long, com.datanote.domain.governance.model.DnQualityRun> latest = new HashMap<>();
        Map<Long, com.datanote.domain.governance.model.DnQualityRun> latestSuccess = new HashMap<>();
        if (!ids.isEmpty()) {
            List<com.datanote.domain.governance.model.DnQualityRun> runs = qualityRunMapper.selectList(
                    new LambdaQueryWrapper<com.datanote.domain.governance.model.DnQualityRun>()
                            .in(com.datanote.domain.governance.model.DnQualityRun::getRuleId, ids)
                            .orderByDesc(com.datanote.domain.governance.model.DnQualityRun::getId));
            if (runs != null) {
                for (com.datanote.domain.governance.model.DnQualityRun run : runs) {
                    if (run == null || run.getRuleId() == null) continue;
                    latest.putIfAbsent(run.getRuleId(), run);
                    if ("success".equalsIgnoreCase(run.getRunStatus())) latestSuccess.putIfAbsent(run.getRuleId(), run);
                }
            }
        }
        long ok = 0, bad = 0, neverRun = 0, abnormal = 0;
        double sum = 0; int n = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DnProjectAsset a : rules) {
            com.datanote.domain.governance.model.DnQualityRun run = latest.get(a.getAssetId());
            if (run == null) { neverRun++; continue; }
            String st = run.getRunStatus() == null ? "" : run.getRunStatus().toLowerCase();
            if ("success".equals(st)) ok++;
            else if ("failed".equals(st)) bad++;
            else abnormal++; // error 等执行异常
            if (recent.size() < 8) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", run.getStartedAt() == null ? null : run.getStartedAt().toString());
                m.put("name", a.getAssetName());
                m.put("status", st.toUpperCase());
                m.put("passRate", run.getPassRate());
                recent.add(m);
            }
            com.datanote.domain.governance.model.DnQualityRun sr = latestSuccess.get(a.getAssetId());
            if (sr != null && sr.getPassRate() != null) { sum += sr.getPassRate().doubleValue(); n++; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rules.size());
        out.put("ok", ok);
        out.put("bad", bad);
        out.put("abnormal", abnormal);
        out.put("neverRun", neverRun);
        out.put("avgPassPct", n > 0 ? (int) Math.round(sum / n) : null); // B5: 健康分质量维口径
        out.put("evaluable", n);
        out.put("recent", recent);
        return out;
    }

    /** 合成活动流：成员加入 / 资产绑定 / 发布事件，按时间倒序取前 15。 */
    private List<Map<String, Object>> buildActivity(Long projectId, List<DnProjectRelease> releases, List<DnProjectAsset> assets) {
        List<Object[]> events = new ArrayList<>(); // [LocalDateTime, kind, text]
        List<DnProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId));
        if (members != null) for (DnProjectMember m : members) {
            if (m == null) continue;
            events.add(new Object[]{m.getCreatedAt(), "member",
                    "成员 " + m.getUsername() + " 加入（" + ProjectRoles.label(m.getProjectRole()) + "）"});
        }
        if (assets != null) for (DnProjectAsset a : assets) {
            if (a == null) continue;
            events.add(new Object[]{a.getCreatedAt(), "asset",
                    "绑定" + ProjectAssetService.typeLabel(a.getAssetType()) + " " + (a.getAssetName() == null ? a.getAssetId() : a.getAssetName())});
        }
        if (releases == null) releases = new ArrayList<>();
        for (DnProjectRelease rel : releases) {
            if (rel == null) continue;
            LocalDateTime at = rel.getReleasedAt() != null ? rel.getReleasedAt()
                    : (rel.getSubmittedAt() != null ? rel.getSubmittedAt() : rel.getCreatedAt());
            events.add(new Object[]{at, "release", "发布 v" + rel.getVersionNo() + " " + relStatusLabel(rel.getStatus())});
        }
        events.sort((x, y) -> {
            LocalDateTime a = (LocalDateTime) x[0], b = (LocalDateTime) y[0];
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return b.compareTo(a);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < events.size() && i < 15; i++) {
            Object[] e = events.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e[0] == null ? null : e[0].toString());
            m.put("kind", e[1]);
            m.put("text", e[2]);
            out.add(m);
        }
        return out;
    }

    private static String relStatusLabel(String s) {
        switch (s == null ? "" : s) {
            case "PENDING": return "待审批";
            case "APPROVED": return "已通过";
            case "REJECTED": return "已驳回";
            case "RELEASED": return "已发布";
            case "ROLLED_BACK": return "已回滚";
            default: return s;
        }
    }
}
