package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectMapper;
import com.datanote.domain.project.mapper.DnProjectReleaseMapper;
import com.datanote.domain.project.mapper.DnProjectTaskMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectAccess;
import com.datanote.domain.project.model.DnProjectFavorite;
import com.datanote.domain.project.model.DnProjectRelease;
import com.datanote.domain.project.model.DnProjectTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** 工作台首页聚合 + 多项目对比。 */
@Service
@RequiredArgsConstructor
public class ProjectHomeService {

    private final ProjectService projectService;
    private final ProjectFavoriteService favoriteService;
    private final ProjectOverviewService overviewService;
    private final DnProjectMapper projectMapper;
    private final DnProjectReleaseMapper releaseMapper;
    private final DnProjectTaskMapper taskMapper;
    private final ProjectReleaseService releaseService;   // N1: 审批权过滤
    private final com.datanote.domain.governance.mapper.DnGovernanceIssueMapper issueMapper;   // II-2: 指给我的工单

    public Map<String, Object> home() {
        String user = ProjectService.currentUser();
        List<DnProject> all = projectService.list(null);
        if (all == null) all = new ArrayList<>();   // list 理论可返回 null
        Map<Long, DnProject> byId = new HashMap<>();
        for (DnProject p : all) if (p != null) byId.put(p.getId(), p);

        Map<String, Object> r = new LinkedHashMap<>();
        // 我负责
        List<DnProject> mine = new ArrayList<>();
        for (DnProject p : all) if (p != null && user.equals(p.getOwner())) mine.add(p);
        r.put("myProjects", mine);
        // 收藏
        List<DnProject> favs = new ArrayList<>();
        List<DnProjectFavorite> favList = favoriteService.listFavorites();
        if (favList != null) for (DnProjectFavorite f : favList) {
            if (f == null) continue;
            DnProject p = byId.get(f.getProjectId());
            if (p != null) favs.add(p);
        }
        r.put("favorites", favs);
        // 最近访问
        List<Map<String, Object>> recent = new ArrayList<>();
        List<DnProjectAccess> recentAccess = favoriteService.recent(8);
        if (recentAccess != null) for (DnProjectAccess a : recentAccess) {
            if (a == null) continue;
            DnProject p = byId.get(a.getProjectId());
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("accessAt", a.getAccessAt() == null ? null : a.getAccessAt().toString());
            recent.add(m);
        }
        r.put("recent", recent);
        // 待我审批（PENDING 发布; N1: 仅我有审批权的项目才算"待我"）
        List<DnProjectRelease> pending = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getStatus, "PENDING").orderByDesc(DnProjectRelease::getId).last("LIMIT 50"));
        Map<Long, Boolean> canApprove = new HashMap<>();
        List<Map<String, Object>> approvals = new ArrayList<>();
        if (pending != null) for (DnProjectRelease rel : pending) {
            if (rel == null) continue;
            DnProject p = byId.get(rel.getProjectId());
            if (p == null) continue; // 项目已删，跳过
            Boolean ok = canApprove.computeIfAbsent(rel.getProjectId(),
                    pid -> ProjectRoles.can(releaseService.roleOf(pid, user), "release:approve"));
            if (!ok) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("releaseId", rel.getId());
            m.put("projectId", rel.getProjectId());
            m.put("projectName", p == null ? ("#" + rel.getProjectId()) : p.getProjectName());
            m.put("versionNo", rel.getVersionNo());
            m.put("title", rel.getTitle());
            m.put("submittedBy", rel.getSubmittedBy());
            approvals.add(m);
        }
        r.put("pendingApprovals", approvals);
        // 我的待办（指派给我、未完成）
        List<DnProjectTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<DnProjectTask>()
                .eq(DnProjectTask::getAssignee, user).ne(DnProjectTask::getStatus, "DONE")
                .orderByDesc(DnProjectTask::getId).last("LIMIT 50"));
        List<Map<String, Object>> myTasks = new ArrayList<>();
        if (tasks != null) for (DnProjectTask t : tasks) {
            if (t == null) continue;
            DnProject p = byId.get(t.getProjectId());
            if (p == null) continue; // 项目已删，跳过
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("projectId", t.getProjectId());
            m.put("projectName", p == null ? ("#" + t.getProjectId()) : p.getProjectName());
            m.put("title", t.getTitle());
            m.put("status", t.getStatus());
            m.put("priority", t.getPriority());
            m.put("dueDate", t.getDueDate() == null ? null : t.getDueDate().toString());
            myTasks.add(m);
        }
        r.put("myTasks", myTasks);
        // II-2: 我提交的发布(待审批+被驳回, 驳回带意见——提交人最关心"为什么被拒")
        List<DnProjectRelease> mine2 = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getSubmittedBy, user)
                .in(DnProjectRelease::getStatus, "PENDING", "REJECTED")
                .orderByDesc(DnProjectRelease::getId).last("LIMIT 20"));
        List<Map<String, Object>> mySubmissions = new ArrayList<>();
        if (mine2 != null) for (DnProjectRelease rel : mine2) {
            if (rel == null) continue;
            DnProject p = byId.get(rel.getProjectId());
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("releaseId", rel.getId());
            m.put("projectId", rel.getProjectId());
            m.put("projectName", p.getProjectName());
            m.put("versionNo", rel.getVersionNo());
            m.put("title", rel.getTitle());
            m.put("status", rel.getStatus());
            m.put("approveComment", rel.getApproveComment());
            mySubmissions.add(m);
        }
        r.put("mySubmissions", mySubmissions);
        // II-2: 指给我的未闭环工单(服务端按 currentUser 过滤——前端拿不到当前用户)
        List<com.datanote.domain.governance.model.DnGovernanceIssue> issues = issueMapper.selectList(
                new LambdaQueryWrapper<com.datanote.domain.governance.model.DnGovernanceIssue>()
                        .eq(com.datanote.domain.governance.model.DnGovernanceIssue::getOwner, user)
                        .in(com.datanote.domain.governance.model.DnGovernanceIssue::getStatus, "OPEN", "FIXING")
                        .orderByDesc(com.datanote.domain.governance.model.DnGovernanceIssue::getId).last("LIMIT 10"));
        List<Map<String, Object>> myIssues = new ArrayList<>();
        if (issues != null) for (com.datanote.domain.governance.model.DnGovernanceIssue i : issues) {
            if (i == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("title", i.getTitle());
            m.put("severity", i.getSeverity());
            m.put("status", i.getStatus());
            myIssues.add(m);
        }
        r.put("myIssues", myIssues);
        r.put("totalProjects", all.size());
        return r;
    }

}
