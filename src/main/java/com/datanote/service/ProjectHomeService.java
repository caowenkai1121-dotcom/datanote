package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectMapper;
import com.datanote.mapper.DnProjectReleaseMapper;
import com.datanote.mapper.DnProjectTaskMapper;
import com.datanote.model.DnProject;
import com.datanote.model.DnProjectAccess;
import com.datanote.model.DnProjectFavorite;
import com.datanote.model.DnProjectRelease;
import com.datanote.model.DnProjectTask;
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

    public Map<String, Object> home() {
        String user = ProjectService.currentUser();
        List<DnProject> all = projectService.list(null);
        Map<Long, DnProject> byId = new HashMap<>();
        for (DnProject p : all) byId.put(p.getId(), p);

        Map<String, Object> r = new LinkedHashMap<>();
        // 我负责
        List<DnProject> mine = new ArrayList<>();
        for (DnProject p : all) if (user.equals(p.getOwner())) mine.add(p);
        r.put("myProjects", mine);
        // 收藏
        List<DnProject> favs = new ArrayList<>();
        for (DnProjectFavorite f : favoriteService.listFavorites()) {
            DnProject p = byId.get(f.getProjectId());
            if (p != null) favs.add(p);
        }
        r.put("favorites", favs);
        // 最近访问
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DnProjectAccess a : favoriteService.recent(8)) {
            DnProject p = byId.get(a.getProjectId());
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("accessAt", a.getAccessAt() == null ? null : a.getAccessAt().toString());
            recent.add(m);
        }
        r.put("recent", recent);
        // 待我审批（PENDING 发布）
        List<DnProjectRelease> pending = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getStatus, "PENDING").orderByDesc(DnProjectRelease::getId).last("LIMIT 50"));
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (DnProjectRelease rel : pending) {
            DnProject p = byId.get(rel.getProjectId());
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
        for (DnProjectTask t : tasks) {
            DnProject p = byId.get(t.getProjectId());
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
        r.put("totalProjects", all.size());
        return r;
    }

    /** 多项目对比（最多 8 个）。 */
    public List<Map<String, Object>> compare(List<Long> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ids == null) return out;
        int n = 0;
        for (Long id : ids) {
            if (id == null || n++ >= 8) continue;
            try {
                Map<String, Object> ov = overviewService.overview(id);
                DnProject p = (DnProject) ov.get("project");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("projectName", p == null ? ("#" + id) : p.getProjectName());
                m.put("assetTotal", ov.get("assetTotal"));
                m.put("memberCount", ov.get("memberCount"));
                m.put("releaseTotal", ov.get("releaseTotal"));
                m.put("releasePending", ov.get("releasePending"));
                out.add(m);
            } catch (Exception ignore) {
                // 跳过无效项目
            }
        }
        return out;
    }
}
