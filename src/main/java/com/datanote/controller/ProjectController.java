package com.datanote.controller;

import com.datanote.model.DnProject;
import com.datanote.model.R;
import com.datanote.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 项目管理 Controller。 */
@Slf4j
@RestController
@RequestMapping("/api/project")
@Tag(name = "项目管理", description = "项目 CRUD、成员、资产、发布")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final com.datanote.service.ProjectMemberService projectMemberService;
    private final com.datanote.service.ProjectAssetService projectAssetService;
    private final com.datanote.service.ProjectOverviewService projectOverviewService;
    private final com.datanote.service.ProjectReleaseService projectReleaseService;
    private final com.datanote.service.ProjectSettingService projectSettingService;
    private final com.datanote.service.ProjectTagService projectTagService;
    private final com.datanote.service.ProjectFavoriteService projectFavoriteService;
    private final com.datanote.service.ProjectActivityService projectActivityService;
    private final com.datanote.service.ProjectTaskService projectTaskService;
    private final com.datanote.service.ProjectCollabService projectCollabService;

    @Operation(summary = "项目列表")
    @GetMapping("/list")
    public R<List<DnProject>> list(@RequestParam(required = false) String status) {
        return R.ok(projectService.list(status));
    }

    @Operation(summary = "项目详情")
    @GetMapping("/{id}")
    public R<DnProject> getById(@PathVariable Long id) {
        try {
            return R.ok(projectService.getById(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "保存项目(新建/编辑)")
    @PostMapping("/save")
    public R<DnProject> save(@RequestBody DnProject project) {
        try {
            return R.ok(projectService.save(project));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "归档项目")
    @PostMapping("/{id}/archive")
    public R<String> archive(@PathVariable Long id) {
        try {
            projectService.archive(id);
            return R.ok("已归档");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除项目(软删除)")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        try {
            projectService.delete(id);
            return R.ok("已删除");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM-M2：成员与项目角色 =====

    // ===== PM2-M1：标签 + 收藏/置顶/最近访问 =====

    @Operation(summary = "标签列表")
    @GetMapping("/tags")
    public R<List<com.datanote.model.DnProjectTag>> tags() {
        return R.ok(projectTagService.listTags());
    }

    @Operation(summary = "新建标签")
    @PostMapping("/tags")
    public R<com.datanote.model.DnProjectTag> createTag(@RequestBody java.util.Map<String, String> body) {
        try {
            return R.ok(projectTagService.createTag(body.get("tagName"), body.get("tagColor")));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除标签")
    @DeleteMapping("/tags/{tagId}")
    public R<String> deleteTag(@PathVariable Long tagId) {
        projectTagService.deleteTag(tagId);
        return R.ok("已删除");
    }

    @Operation(summary = "全部项目-标签关联")
    @GetMapping("/tag-mappings")
    public R<List<com.datanote.model.DnProjectTagMapping>> tagMappings() {
        return R.ok(projectTagService.allMappings());
    }

    @Operation(summary = "设置项目标签")
    @PostMapping("/{id}/tags")
    public R<String> setProjectTags(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        try {
            projectService.getById(id);
            java.util.List<Long> ids = new java.util.ArrayList<>();
            Object arr = body.get("tagIds");
            if (arr instanceof java.util.List) {
                for (Object o : (java.util.List<?>) arr) ids.add(Long.valueOf(String.valueOf(o)));
            }
            projectTagService.setProjectTags(id, ids);
            return R.ok("已保存");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "切换收藏")
    @PostMapping("/{id}/favorite")
    public R<java.util.Map<String, Object>> toggleFavorite(@PathVariable Long id) {
        boolean fav = projectFavoriteService.toggleFavorite(id);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("favorited", fav);
        return R.ok(m);
    }

    @Operation(summary = "置顶设置")
    @PostMapping("/{id}/pin")
    public R<String> setPin(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned")) || "true".equals(String.valueOf(body.get("pinned")));
        projectFavoriteService.setPinned(id, pinned);
        return R.ok("已更新");
    }

    @Operation(summary = "我的收藏")
    @GetMapping("/favorites")
    public R<List<com.datanote.model.DnProjectFavorite>> favorites() {
        return R.ok(projectFavoriteService.listFavorites());
    }

    @Operation(summary = "记录访问")
    @PostMapping("/{id}/visit")
    public R<String> visit(@PathVariable Long id) {
        projectFavoriteService.recordAccess(id);
        return R.ok("ok");
    }

    @Operation(summary = "最近访问")
    @GetMapping("/recent")
    public R<List<com.datanote.model.DnProjectAccess>> recent(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(projectFavoriteService.recent(limit));
    }

    @Operation(summary = "项目角色权限矩阵")
    @GetMapping("/roles")
    public R<List<java.util.Map<String, Object>>> roles() {
        return R.ok(com.datanote.service.ProjectRoles.matrix());
    }

    @Operation(summary = "项目成员列表")
    @GetMapping("/{id}/members")
    public R<List<com.datanote.model.DnProjectMember>> members(@PathVariable Long id) {
        try {
            return R.ok(projectMemberService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "添加成员")
    @PostMapping("/{id}/members")
    public R<com.datanote.model.DnProjectMember> addMember(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        try {
            return R.ok(projectMemberService.add(id, body.get("username"), body.get("role")));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "修改成员角色")
    @PutMapping("/{id}/members/{memberId}")
    public R<String> changeRole(@PathVariable Long id, @PathVariable Long memberId, @RequestBody java.util.Map<String, String> body) {
        try {
            projectMemberService.changeRole(memberId, body.get("role"));
            return R.ok("已更新");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{memberId}")
    public R<String> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        try {
            projectMemberService.remove(memberId);
            return R.ok("已移除");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "项目概览大盘")
    @GetMapping("/{id}/overview")
    public R<java.util.Map<String, Object>> overview(@PathVariable Long id) {
        try {
            return R.ok(projectOverviewService.overview(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM2-M4：公告 + 成员邀请 =====

    @Operation(summary = "公告列表")
    @GetMapping("/{id}/announcements")
    public R<List<com.datanote.model.DnProjectAnnouncement>> announcements(@PathVariable Long id) {
        try { return R.ok(projectCollabService.listAnnouncements(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "发布公告")
    @PostMapping("/{id}/announcements")
    public R<com.datanote.model.DnProjectAnnouncement> createAnnouncement(@PathVariable Long id, @RequestBody com.datanote.model.DnProjectAnnouncement a) {
        try { return R.ok(projectCollabService.createAnnouncement(id, a)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/{id}/announcements/{annId}")
    public R<String> deleteAnnouncement(@PathVariable Long id, @PathVariable Long annId) {
        try { projectCollabService.deleteAnnouncement(id, annId); return R.ok("已删除"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "邀请列表")
    @GetMapping("/{id}/invites")
    public R<List<com.datanote.model.DnProjectInvite>> invites(@PathVariable Long id) {
        try { return R.ok(projectCollabService.listInvites(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "创建邀请")
    @PostMapping("/{id}/invites")
    public R<com.datanote.model.DnProjectInvite> createInvite(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        try { return R.ok(projectCollabService.createInvite(id, body.get("role"), body.get("invitee"))); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "接受邀请")
    @PostMapping("/invites/accept")
    public R<String> acceptInvite(@RequestBody java.util.Map<String, String> body) {
        try { projectCollabService.acceptInvite(body.get("token")); return R.ok("已加入"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "处理邀请(拒绝/取消)")
    @PostMapping("/{id}/invites/{inviteId}/status")
    public R<String> inviteStatus(@PathVariable Long id, @PathVariable Long inviteId, @RequestBody java.util.Map<String, String> body) {
        try { projectCollabService.updateInviteStatus(inviteId, body.get("status")); return R.ok("已更新"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    // ===== PM2-M3：任务待办 + 里程碑 =====

    @Operation(summary = "任务列表")
    @GetMapping("/{id}/tasks")
    public R<List<com.datanote.model.DnProjectTask>> tasks(@PathVariable Long id) {
        try { return R.ok(projectTaskService.listTasks(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "保存任务")
    @PostMapping("/{id}/tasks")
    public R<com.datanote.model.DnProjectTask> saveTask(@PathVariable Long id, @RequestBody com.datanote.model.DnProjectTask task) {
        try { return R.ok(projectTaskService.saveTask(id, task)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{id}/tasks/{taskId}")
    public R<String> deleteTask(@PathVariable Long id, @PathVariable Long taskId) {
        try { projectTaskService.deleteTask(id, taskId); return R.ok("已删除"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "里程碑列表")
    @GetMapping("/{id}/milestones")
    public R<List<com.datanote.model.DnProjectMilestone>> milestones(@PathVariable Long id) {
        try { return R.ok(projectTaskService.listMilestones(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "保存里程碑")
    @PostMapping("/{id}/milestones")
    public R<com.datanote.model.DnProjectMilestone> saveMilestone(@PathVariable Long id, @RequestBody com.datanote.model.DnProjectMilestone m) {
        try { return R.ok(projectTaskService.saveMilestone(id, m)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除里程碑")
    @DeleteMapping("/{id}/milestones/{milestoneId}")
    public R<String> deleteMilestone(@PathVariable Long id, @PathVariable Long milestoneId) {
        try { projectTaskService.deleteMilestone(id, milestoneId); return R.ok("已删除"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "项目健康分")
    @GetMapping("/{id}/health")
    public R<java.util.Map<String, Object>> health(@PathVariable Long id) {
        try {
            java.util.Map<String, Object> ov = projectOverviewService.overview(id);
            long assetTotal = ((Number) ov.getOrDefault("assetTotal", 0L)).longValue();
            long memberCount = ((Number) ov.getOrDefault("memberCount", 0L)).longValue();
            long releaseTotal = ((Number) ov.getOrDefault("releaseTotal", 0L)).longValue();
            long actCnt = ov.get("activity") instanceof java.util.List ? ((java.util.List<?>) ov.get("activity")).size() : 0;
            long jobSuccess = 0, jobFailed = 0;
            Object jr = ov.get("jobRuns");
            if (jr instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) jr;
                Object s = m.get("success"), f = m.get("failed");
                if (s instanceof Number) jobSuccess = ((Number) s).longValue();
                if (f instanceof Number) jobFailed = ((Number) f).longValue();
            }
            return R.ok(com.datanote.service.ProjectHealthScorer.score(assetTotal, memberCount, releaseTotal, jobSuccess, jobFailed, actCnt));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "项目活动审计")
    @GetMapping("/{id}/activities")
    public R<List<com.datanote.model.DnAuditLog>> activities(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        try {
            return R.ok(projectActivityService.list(id, limit));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM-M3：资产纳管 =====

    @Operation(summary = "项目资产列表")
    @GetMapping("/{id}/assets")
    public R<List<com.datanote.model.DnProjectAsset>> assets(@PathVariable Long id) {
        try {
            return R.ok(projectAssetService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "绑定资产")
    @PostMapping("/{id}/assets")
    public R<com.datanote.model.DnProjectAsset> bindAsset(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        try {
            Object aid = body.get("assetId");
            Long assetId = aid == null ? null : Long.valueOf(String.valueOf(aid));
            return R.ok(projectAssetService.bind(id, String.valueOf(body.get("assetType")), assetId,
                    body.get("assetName") == null ? null : String.valueOf(body.get("assetName"))));
        } catch (NumberFormatException e) {
            return R.fail("资产ID非法");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "解绑资产")
    @DeleteMapping("/{id}/assets/{rowId}")
    public R<String> unbindAsset(@PathVariable Long id, @PathVariable Long rowId) {
        try {
            projectService.getById(id);
            projectAssetService.unbind(rowId);
            return R.ok("已解绑");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "资产反查所属项目")
    @GetMapping("/asset-projects")
    public R<List<java.util.Map<String, Object>>> assetProjects(@RequestParam String type, @RequestParam Long assetId) {
        return R.ok(projectAssetService.projectsOfAsset(type, assetId));
    }

    @Operation(summary = "可绑定资产候选")
    @GetMapping("/{id}/asset-candidates")
    public R<List<java.util.Map<String, Object>>> assetCandidates(@PathVariable Long id, @RequestParam String type) {
        try {
            return R.ok(projectAssetService.candidates(id, type));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM-E3：项目设置（资源配额 + 环境参数映射） =====

    @Operation(summary = "项目资源配额")
    @GetMapping("/{id}/quota")
    public R<com.datanote.model.DnProjectQuota> getQuota(@PathVariable Long id) {
        try {
            return R.ok(projectSettingService.getQuota(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "保存资源配额")
    @PostMapping("/{id}/quota")
    public R<com.datanote.model.DnProjectQuota> saveQuota(@PathVariable Long id, @RequestBody com.datanote.model.DnProjectQuota quota) {
        try {
            return R.ok(projectSettingService.saveQuota(id, quota));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "环境参数列表")
    @GetMapping("/{id}/env-params")
    public R<List<com.datanote.model.DnProjectEnvParam>> envParams(@PathVariable Long id) {
        try {
            return R.ok(projectSettingService.listEnvParams(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "保存环境参数")
    @PostMapping("/{id}/env-params")
    public R<com.datanote.model.DnProjectEnvParam> saveEnvParam(@PathVariable Long id, @RequestBody com.datanote.model.DnProjectEnvParam param) {
        try {
            return R.ok(projectSettingService.saveEnvParam(id, param));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除环境参数")
    @DeleteMapping("/{id}/env-params/{paramId}")
    public R<String> deleteEnvParam(@PathVariable Long id, @PathVariable Long paramId) {
        try {
            projectService.getById(id);
            projectSettingService.deleteEnvParam(id, paramId);
            return R.ok("已删除");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM-M5：发布管理 =====

    @Operation(summary = "项目发布版本列表")
    @GetMapping("/{id}/releases")
    public R<List<com.datanote.model.DnProjectRelease>> releases(@PathVariable Long id) {
        try {
            return R.ok(projectReleaseService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "提交发布")
    @PostMapping("/{id}/releases")
    public R<com.datanote.model.DnProjectRelease> submitRelease(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        try {
            return R.ok(projectReleaseService.submit(id, body.get("title"), body.get("content"), body.get("targetEnv")));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "跨项目发布中心")
    @GetMapping("/releases/all")
    public R<List<com.datanote.model.DnProjectRelease>> allReleases(@RequestParam(required = false) String status) {
        return R.ok(projectReleaseService.listAll(status));
    }

    @Operation(summary = "审批通过")
    @PostMapping("/releases/{releaseId}/approve")
    public R<String> approveRelease(@PathVariable Long releaseId, @RequestBody(required = false) java.util.Map<String, String> body) {
        try {
            projectReleaseService.approve(releaseId, body == null ? null : body.get("comment"));
            return R.ok("已通过");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "审批驳回")
    @PostMapping("/releases/{releaseId}/reject")
    public R<String> rejectRelease(@PathVariable Long releaseId, @RequestBody(required = false) java.util.Map<String, String> body) {
        try {
            projectReleaseService.reject(releaseId, body == null ? null : body.get("comment"));
            return R.ok("已驳回");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "发布上线")
    @PostMapping("/releases/{releaseId}/release")
    public R<String> doRelease(@PathVariable Long releaseId) {
        try {
            projectReleaseService.release(releaseId);
            return R.ok("已发布");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "回滚")
    @PostMapping("/releases/{releaseId}/rollback")
    public R<String> rollbackRelease(@PathVariable Long releaseId) {
        try {
            projectReleaseService.rollback(releaseId);
            return R.ok("已回滚");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }
}
