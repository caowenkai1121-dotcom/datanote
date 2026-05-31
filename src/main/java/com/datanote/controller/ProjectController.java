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
        projectAssetService.unbind(rowId);
        return R.ok("已解绑");
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
}
