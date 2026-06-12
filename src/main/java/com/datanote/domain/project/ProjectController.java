package com.datanote.domain.project;

import com.datanote.domain.project.model.DnProject;
import com.datanote.common.model.R;
import com.datanote.domain.project.ProjectService;
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
    private final com.datanote.domain.project.ProjectMemberService projectMemberService;
    private final com.datanote.domain.project.ProjectAssetService projectAssetService;
    private final com.datanote.domain.project.ProjectOverviewService projectOverviewService;
    private final com.datanote.domain.project.ProjectReleaseService projectReleaseService;
    private final com.datanote.domain.project.ProjectTagService projectTagService;
    private final com.datanote.domain.project.ProjectFavoriteService projectFavoriteService;
    private final com.datanote.domain.project.ProjectActivityService projectActivityService;
    private final com.datanote.domain.project.ProjectTaskService projectTaskService;
    private final com.datanote.domain.project.ProjectWikiService projectWikiService;
    private final com.datanote.domain.project.ProjectTemplateService projectTemplateService;
    private final com.datanote.domain.project.ProjectHomeService projectHomeService;
    private final com.datanote.domain.project.ProjectHealthService projectHealthService;   // P2 健康分下沉

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
    public R<List<com.datanote.domain.project.model.DnProjectTag>> tags() {
        return R.ok(projectTagService.listTags());
    }

    @Operation(summary = "新建标签")
    @PostMapping("/tags")
    public R<com.datanote.domain.project.model.DnProjectTag> createTag(@RequestBody java.util.Map<String, String> body) {
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
    public R<List<com.datanote.domain.project.model.DnProjectTagMapping>> tagMappings() {
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
    public R<List<com.datanote.domain.project.model.DnProjectFavorite>> favorites() {
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
    public R<List<com.datanote.domain.project.model.DnProjectAccess>> recent(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(projectFavoriteService.recent(limit));
    }

    @Operation(summary = "项目角色权限矩阵")
    @GetMapping("/roles")
    public R<List<java.util.Map<String, Object>>> roles() {
        return R.ok(com.datanote.domain.project.ProjectRoles.matrix());
    }

    @Operation(summary = "项目成员列表")
    @GetMapping("/{id}/members")
    public R<List<com.datanote.domain.project.model.DnProjectMember>> members(@PathVariable Long id) {
        try {
            return R.ok(projectMemberService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "添加成员")
    @PostMapping("/{id}/members")
    public R<com.datanote.domain.project.model.DnProjectMember> addMember(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
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

    // ===== PM2-M7：项目模板 =====

    @Operation(summary = "模板列表")
    @GetMapping("/templates")
    public R<List<com.datanote.domain.project.model.DnProjectTemplate>> templates() {
        return R.ok(projectTemplateService.list());
    }

    @Operation(summary = "存为模板")
    @PostMapping("/{id}/save-as-template")
    public R<com.datanote.domain.project.model.DnProjectTemplate> saveAsTemplate(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        try { return R.ok(projectTemplateService.saveAsTemplate(id, body.get("name"), body.get("description"))); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "从模板新建项目")
    @PostMapping("/templates/{templateId}/create")
    public R<DnProject> createFromTemplate(@PathVariable Long templateId, @RequestBody java.util.Map<String, String> body) {
        try { return R.ok(projectTemplateService.createFromTemplate(templateId, body.get("projectName"))); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除模板")
    @DeleteMapping("/templates/{templateId}")
    public R<String> deleteTemplate(@PathVariable Long templateId) {
        projectTemplateService.delete(templateId);
        return R.ok("已删除");
    }

    // ===== PM2-M8：工作台首页 + 多项目对比 =====

    @Operation(summary = "工作台首页")
    @GetMapping("/home")
    public R<java.util.Map<String, Object>> home() {
        return R.ok(projectHomeService.home());
    }

    // ===== PM2-M5：文档 Wiki =====

    @Operation(summary = "文档页面列表")
    @GetMapping("/{id}/wiki/pages")
    public R<List<com.datanote.domain.project.model.DnProjectWikiPage>> wikiPages(@PathVariable Long id) {
        try { return R.ok(projectWikiService.listPages(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "文档页面详情")
    @GetMapping("/{id}/wiki/pages/{pageId}")
    public R<com.datanote.domain.project.model.DnProjectWikiPage> wikiPage(@PathVariable Long id, @PathVariable Long pageId) {
        try { return R.ok(projectWikiService.getPage(id, pageId)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "保存文档页面")
    @PostMapping("/{id}/wiki/pages")
    public R<com.datanote.domain.project.model.DnProjectWikiPage> saveWikiPage(@PathVariable Long id, @RequestBody com.datanote.domain.project.model.DnProjectWikiPage page) {
        try { return R.ok(projectWikiService.savePage(id, page)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除文档页面")
    @DeleteMapping("/{id}/wiki/pages/{pageId}")
    public R<String> deleteWikiPage(@PathVariable Long id, @PathVariable Long pageId) {
        try { projectWikiService.deletePage(id, pageId); return R.ok("已删除"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    // (PM2-M4 公告/邀请 已清退: 前端入口 P2 砍除, 后端端点 P4 随之下线——invites/accept 曾是无鉴权加入项目的攻击面)

    // ===== PM2-M3：任务待办 + 里程碑 =====

    @Operation(summary = "任务列表")
    @GetMapping("/{id}/tasks")
    public R<List<com.datanote.domain.project.model.DnProjectTask>> tasks(@PathVariable Long id) {
        try { return R.ok(projectTaskService.listTasks(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "保存任务")
    @PostMapping("/{id}/tasks")
    public R<java.util.Map<String, Object>> saveTask(@PathVariable Long id, @RequestBody com.datanote.domain.project.model.DnProjectTask task) {
        try { return R.ok(projectTaskService.saveTaskAndSync(id, task)); }
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
    public R<List<com.datanote.domain.project.model.DnProjectMilestone>> milestones(@PathVariable Long id) {
        try { return R.ok(projectTaskService.listMilestones(id)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "保存里程碑")
    @PostMapping("/{id}/milestones")
    public R<com.datanote.domain.project.model.DnProjectMilestone> saveMilestone(@PathVariable Long id, @RequestBody com.datanote.domain.project.model.DnProjectMilestone m) {
        try { return R.ok(projectTaskService.saveMilestone(id, m)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "删除里程碑")
    @DeleteMapping("/{id}/milestones/{milestoneId}")
    public R<String> deleteMilestone(@PathVariable Long id, @PathVariable Long milestoneId) {
        try { projectTaskService.deleteMilestone(id, milestoneId); return R.ok("已删除"); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "项目健康分(P2 下沉 ProjectHealthService: 终态运行/仅success通过率/超期/积压/近30天协作)")
    @GetMapping("/{id}/health")
    public R<java.util.Map<String, Object>> health(@PathVariable Long id) {
        try {
            return R.ok(projectHealthService.score(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "健康分批量(N7: 项目列表/工作台徽标, 仅活跃项目)")
    @GetMapping("/health/batch")
    public R<java.util.Map<Long, java.util.Map<String, Object>>> healthBatch() {
        return R.ok(projectHealthService.scoreBatch());
    }

    @Operation(summary = "项目活动审计")
    @GetMapping("/{id}/activities")
    public R<List<com.datanote.platform.audit.model.DnAuditLog>> activities(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        try {
            return R.ok(projectActivityService.list(id, limit));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    // ===== PM-M3：资产纳管 =====

    @Operation(summary = "项目资产列表")
    @GetMapping("/{id}/assets")
    public R<List<com.datanote.domain.project.model.DnProjectAsset>> assets(@PathVariable Long id) {
        try {
            return R.ok(projectAssetService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "绑定资产")
    @PostMapping("/{id}/assets")
    public R<com.datanote.domain.project.model.DnProjectAsset> bindAsset(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
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
            projectAssetService.unbind(id, rowId);
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

    @Operation(summary = "任务评论列表(IV-1)")
    @GetMapping("/{id}/tasks/{taskId}/comments")
    public R<List<com.datanote.domain.project.model.DnProjectTaskComment>> taskComments(@PathVariable Long id, @PathVariable Long taskId) {
        try { return R.ok(projectTaskService.listComments(id, taskId)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "新增任务评论(IV-1, author 服务端写入)")
    @PostMapping("/{id}/tasks/{taskId}/comments")
    public R<com.datanote.domain.project.model.DnProjectTaskComment> addTaskComment(@PathVariable Long id, @PathVariable Long taskId, @RequestBody java.util.Map<String, String> body) {
        try { return R.ok(projectTaskService.addComment(id, taskId, body == null ? null : body.get("content"))); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Operation(summary = "按关联实体批量反查任务(N5: 工单/资产侧任务徽标, 防N+1)")
    @GetMapping("/task-refs/batch")
    public R<java.util.Map<Long, List<java.util.Map<String, Object>>>> taskRefsBatch(
            @RequestParam String refType, @RequestParam String ids,
            @RequestParam(required = false, defaultValue = "false") boolean excludeDone) {
        java.util.List<Long> idList = new java.util.ArrayList<>();
        for (String s : ids.split(",")) {
            try { idList.add(Long.valueOf(s.trim())); } catch (NumberFormatException ignore) {}
        }
        return R.ok(projectTaskService.taskRefsBatch(refType, idList, excludeDone));
    }

    @Operation(summary = "资产批量反查所属项目(P1: 列表页归属徽标, 防N+1)")
    @GetMapping("/asset-projects/batch")
    public R<java.util.Map<Long, List<java.util.Map<String, Object>>>> assetProjectsBatch(
            @RequestParam String type, @RequestParam String ids) {
        java.util.List<Long> idList = new java.util.ArrayList<>();
        for (String s : ids.split(",")) {
            try { idList.add(Long.valueOf(s.trim())); } catch (NumberFormatException ignore) {}
        }
        return R.ok(projectAssetService.projectsOfAssetsBatch(type, idList));
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

    // (PM-E3 项目设置已整体清退: 资源配额/环境参数映射均为配置摆设无真实消费方, 表保留弃用)

    // ===== PM-M5：发布管理 =====

    @Operation(summary = "项目发布版本列表")
    @GetMapping("/{id}/releases")
    public R<List<com.datanote.domain.project.model.DnProjectRelease>> releases(@PathVariable Long id) {
        try {
            return R.ok(projectReleaseService.list(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "提交发布")
    @PostMapping("/{id}/releases")
    public R<com.datanote.domain.project.model.DnProjectRelease> submitRelease(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        try {
            return R.ok(projectReleaseService.submit(id, body.get("title"), body.get("content"), body.get("targetEnv"), body.get("assetJson")));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "跨项目发布中心(回填 projectName)")
    @GetMapping("/releases/all")
    public R<List<java.util.Map<String, Object>>> allReleases(@RequestParam(required = false) String status) {
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

    @Operation(summary = "我可审批的项目(P5: 发布中心按钮角色化)")
    @GetMapping("/my-approvable")
    public R<List<Long>> myApprovable() {
        return R.ok(projectReleaseService.myApprovableProjects());
    }

    @Operation(summary = "发布上线(N6 门禁: 资产核验, 警示项可 force, 强规则失败不可)")
    @PostMapping("/releases/{releaseId}/release")
    public R<String> doRelease(@PathVariable Long releaseId,
                               @RequestParam(required = false, defaultValue = "false") boolean force) {
        try {
            projectReleaseService.release(releaseId, force);
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
