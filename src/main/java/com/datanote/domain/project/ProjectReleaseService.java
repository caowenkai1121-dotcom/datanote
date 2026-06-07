package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectReleaseMapper;
import com.datanote.domain.project.model.DnProjectRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 项目发布版本：提交/审批/驳回/发布/回滚，状态机护栏。 */
@Service
@RequiredArgsConstructor
public class ProjectReleaseService {

    private final DnProjectReleaseMapper releaseMapper;
    private final ProjectService projectService;

    public List<DnProjectRelease> list(Long projectId) {
        projectService.getById(projectId);
        return releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId)
                .orderByDesc(DnProjectRelease::getVersionNo));
    }

    /** 跨项目发布中心：status 非空则过滤，按 id 倒序，最多 200。 */
    public List<DnProjectRelease> listAll(String status) {
        LambdaQueryWrapper<DnProjectRelease> w = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) w.eq(DnProjectRelease::getStatus, status.trim());
        w.orderByDesc(DnProjectRelease::getId).last("LIMIT 200");
        return releaseMapper.selectList(w);
    }

    public synchronized DnProjectRelease submit(Long projectId, String title, String content, String targetEnv) {
        projectService.getById(projectId);
        DnProjectRelease r = new DnProjectRelease();
        r.setProjectId(projectId);
        r.setVersionNo(nextVersion(projectId));
        r.setTitle(title == null || title.trim().isEmpty() ? ("发布 v" + r.getVersionNo()) : title.trim());
        r.setContent(content);
        r.setTargetEnv(targetEnv == null || targetEnv.trim().isEmpty() ? "PROD" : targetEnv.trim());
        r.setStatus("PENDING");
        r.setSubmittedBy(ProjectService.currentUser());
        r.setSubmittedAt(LocalDateTime.now());
        releaseMapper.insert(r);
        return r;
    }

    public void approve(Long releaseId, String comment) {
        DnProjectRelease r = get(releaseId);
        ReleaseState.require(r.getStatus(), "APPROVED");
        r.setStatus("APPROVED");
        r.setApprover(ProjectService.currentUser());
        r.setApprovedAt(LocalDateTime.now());
        r.setApproveComment(comment);
        releaseMapper.updateById(r);
    }

    public void reject(Long releaseId, String comment) {
        DnProjectRelease r = get(releaseId);
        ReleaseState.require(r.getStatus(), "REJECTED");
        r.setStatus("REJECTED");
        r.setApprover(ProjectService.currentUser());
        r.setApprovedAt(LocalDateTime.now());
        r.setApproveComment(comment);
        releaseMapper.updateById(r);
    }

    public void release(Long releaseId) {
        DnProjectRelease r = get(releaseId);
        ReleaseState.require(r.getStatus(), "RELEASED");
        r.setStatus("RELEASED");
        r.setReleasedAt(LocalDateTime.now());
        releaseMapper.updateById(r);
    }

    public void rollback(Long releaseId) {
        DnProjectRelease r = get(releaseId);
        ReleaseState.require(r.getStatus(), "ROLLED_BACK");
        r.setStatus("ROLLED_BACK");
        releaseMapper.updateById(r);
    }

    private DnProjectRelease get(Long id) {
        DnProjectRelease r = releaseMapper.selectById(id);
        if (r == null) throw new IllegalArgumentException("发布版本不存在: " + id);
        return r;
    }

    private int nextVersion(Long projectId) {
        List<DnProjectRelease> rs = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId)
                .orderByDesc(DnProjectRelease::getVersionNo).last("LIMIT 1"));
        return rs.isEmpty() || rs.get(0).getVersionNo() == null ? 1 : rs.get(0).getVersionNo() + 1;
    }
}
