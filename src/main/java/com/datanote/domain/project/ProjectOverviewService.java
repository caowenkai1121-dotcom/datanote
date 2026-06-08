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

    public Map<String, Object> overview(Long projectId) {
        DnProject p = projectService.getById(projectId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("project", p);

        Map<String, Long> counts = assetService.countsByType(projectId);
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

        r.put("activity", buildActivity(projectId, releases));
        r.put("jobRuns", boundJobRuns(projectId));
        return r;
    }

    /** 绑定的同步任务最近运行聚合：按最新一次执行统计成功/失败/运行中 + 最近列表。 */
    private Map<String, Object> boundJobRuns(Long projectId) {
        List<DnProjectAsset> jobs = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId).eq(DnProjectAsset::getAssetType, "SYNC_JOB"));
        if (jobs == null) jobs = new ArrayList<>();   // selectList 理论可返回 null
        long success = 0, failed = 0, running = 0, neverRun = 0, other = 0;
        List<Object[]> recents = new ArrayList<>(); // [time, name, status]
        // 收集绑定的同步任务 id(去重剔空),一次性批量查执行记录,消 N+1(原逐任务 selectOne)
        Set<Long> jobIds = new LinkedHashSet<>();
        for (DnProjectAsset job : jobs) {
            if (job != null && job.getAssetId() != null) jobIds.add(job.getAssetId());
        }
        Map<Long, com.datanote.domain.orchestration.model.DnTaskExecution> latestByJob = new HashMap<>();
        if (!jobIds.isEmpty()) {
            List<com.datanote.domain.orchestration.model.DnTaskExecution> execs = taskExecutionMapper.selectList(
                    new LambdaQueryWrapper<com.datanote.domain.orchestration.model.DnTaskExecution>()
                            .in(com.datanote.domain.orchestration.model.DnTaskExecution::getSyncTaskId, jobIds)
                            .eq(com.datanote.domain.orchestration.model.DnTaskExecution::getTaskType, "DbSync")
                            .orderByDesc(com.datanote.domain.orchestration.model.DnTaskExecution::getId));
            if (execs != null) {
                for (com.datanote.domain.orchestration.model.DnTaskExecution e : execs) {
                    if (e == null || e.getSyncTaskId() == null) continue;
                    // id 倒序,每个任务首次出现即最新一条(等价原 LIMIT 1)
                    latestByJob.putIfAbsent(e.getSyncTaskId(), e);
                }
            }
        }
        for (DnProjectAsset job : jobs) {
            if (job == null) continue;
            com.datanote.domain.orchestration.model.DnTaskExecution e =
                    job.getAssetId() == null ? null : latestByJob.get(job.getAssetId());
            if (e == null) {
                neverRun++;
                continue;
            }
            String st = e.getStatus();
            if ("SUCCESS".equals(st)) success++;
            else if ("FAILED".equals(st)) failed++;
            else if ("RUNNING".equals(st)) running++;
            else other++; // STOPPED 等其它状态，保证计数与 total 自洽
            recents.add(new Object[]{e.getStartTime(), job.getAssetName(), st});
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
        out.put("total", jobs.size());
        out.put("success", success);
        out.put("failed", failed);
        out.put("running", running);
        out.put("other", other);
        out.put("neverRun", neverRun);
        out.put("recent", recent);
        return out;
    }

    /** 合成活动流：成员加入 / 资产绑定 / 发布事件，按时间倒序取前 15。 */
    private List<Map<String, Object>> buildActivity(Long projectId, List<DnProjectRelease> releases) {
        List<Object[]> events = new ArrayList<>(); // [LocalDateTime, kind, text]
        List<DnProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId));
        if (members != null) for (DnProjectMember m : members) {
            if (m == null) continue;
            events.add(new Object[]{m.getCreatedAt(), "member",
                    "成员 " + m.getUsername() + " 加入（" + ProjectRoles.label(m.getProjectRole()) + "）"});
        }
        List<DnProjectAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId));
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
