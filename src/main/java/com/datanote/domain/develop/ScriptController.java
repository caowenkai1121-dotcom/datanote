package com.datanote.domain.develop;

import com.datanote.common.model.R;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptFolder;
import com.datanote.domain.develop.model.DnScriptVersion;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.develop.dto.MoveScriptRequest;
import com.datanote.domain.develop.dto.RenameFolderRequest;
import com.datanote.domain.develop.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 脚本 & 文件夹管理 Controller — 参数校验 + 调用 Service
 */
@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
@Tag(name = "脚本管理", description = "脚本与文件夹的增删改查、同步任务管理")
public class ScriptController {

    private final ScriptService scriptService;

    @Operation(summary = "获取脚本树")
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        return R.ok(scriptService.getTree());
    }

    @Operation(summary = "根据ID查询脚本")
    @GetMapping("/{id}")
    public R<DnScript> getById(@PathVariable Long id) {
        return R.ok(scriptService.getById(id));
    }

    @Operation(summary = "保存脚本")
    @PostMapping("/save")
    public R<DnScript> save(@RequestBody DnScript script) {
        return R.ok(scriptService.save(script));
    }

    @Operation(summary = "更新脚本基本信息（不创建版本）")
    @PutMapping("/{id}/basic-info")
    public R<Void> updateBasicInfo(@PathVariable Long id, @RequestBody Map<String, String> body) {
        scriptService.updateBasicInfo(id, body);
        return R.ok(null);
    }

    @Operation(summary = "重命名脚本(全站#4: 原前端假动作刷新即回弹)")
    @PutMapping("/{id}/rename")
    public R<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        scriptService.rename(id, body == null ? null : body.get("name"));
        return R.ok(null);
    }

    @Operation(summary = "更新脚本库名")
    @PutMapping("/{id}/database-name")
    public R<Void> updateDatabaseName(@PathVariable Long id, @RequestBody Map<String, String> body) {
        scriptService.updateDatabaseName(id, body.get("databaseName"));
        return R.ok(null);
    }

    @Operation(summary = "获取脚本版本历史")
    @GetMapping("/{id}/versions")
    public R<List<DnScriptVersion>> listVersions(@PathVariable Long id) {
        return R.ok(scriptService.listVersions(id));
    }

    @Operation(summary = "回滚脚本到指定历史版本")
    @PostMapping("/{id}/rollback/{versionId}")
    public R<DnScript> rollback(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(scriptService.rollbackToVersion(id, versionId));
    }

    @Operation(summary = "创建文件夹")
    @PostMapping("/folder")
    public R<DnScriptFolder> createFolder(@RequestBody DnScriptFolder folder) {
        return R.ok(scriptService.createFolder(folder));
    }

    @Operation(summary = "重命名文件夹")
    @PostMapping("/folder/rename")
    public R<String> renameFolder(@RequestBody RenameFolderRequest body) {
        scriptService.renameFolder(body.getId(), body.getFolderName());
        return R.ok("重命名成功");
    }

    @Operation(summary = "更新文件夹排序")
    @PostMapping("/folder/sort")
    public R<String> updateFolderSort(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
        Integer sortOrder = body.get("sortOrder") == null ? 0 : Integer.valueOf(body.get("sortOrder").toString());
        scriptService.updateFolderSort(id, sortOrder);
        return R.ok("已更新排序");
    }

    @Operation(summary = "删除文件夹")
    @DeleteMapping("/folder/{id}")
    public R<String> deleteFolder(@PathVariable Long id) {
        scriptService.deleteFolder(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "删除脚本")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        scriptService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "移动脚本到目标文件夹")
    @PutMapping("/{id}/move")
    public R<String> moveScript(@PathVariable Long id, @RequestBody MoveScriptRequest body) {
        scriptService.moveScript(id, body.getTargetFolderId());
        return R.ok("移动成功");
    }

    @GetMapping("/all-with-content")
    public R<List<Map<String, Object>>> allWithContent() {
        return R.ok(scriptService.allWithContent());
    }

    @Operation(summary = "同步任务详情(供前端取版本基线 updatedAt)")
    @GetMapping("/sync-task/{id}")
    public R<DnSyncTask> getSyncTask(@PathVariable Long id) {
        return R.ok(scriptService.getSyncTask(id));
    }

    @Operation(summary = "保存同步任务")
    @PostMapping("/sync-task")
    public R<DnSyncTask> saveSyncTask(@RequestBody DnSyncTask task) {
        return R.ok(scriptService.saveSyncTask(task));
    }

    @Operation(summary = "删除同步任务")
    @DeleteMapping("/sync-task/{id}")
    public R<String> deleteSyncTask(@PathVariable Long id) {
        scriptService.deleteSyncTask(id);
        return R.ok("删除成功");
    }
}
