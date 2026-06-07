package com.datanote.sync.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.mapper.DnSyncFolderMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.domain.integration.model.DnSyncFolder;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关系库同步任务文件夹 Controller。
 * <p>仅返回全部文件夹平铺列表，前端自行建树。删除文件夹时，其下任务的 folder_id 批量回置 0。
 */
@Slf4j
@RestController
@RequestMapping("/api/sync-folder")
@Tag(name = "关系库同步文件夹", description = "同步任务文件夹的增删改查")
@RequiredArgsConstructor
public class SyncFolderController {

    private final DnSyncFolderMapper folderMapper;
    private final DnSyncJobMapper syncJobMapper;

    @Operation(summary = "文件夹列表（平铺，前端建树）")
    @GetMapping("/list")
    public R<List<DnSyncFolder>> list() {
        return R.ok(folderMapper.selectList(null));
    }

    @Operation(summary = "保存文件夹（新增/改名）")
    @PostMapping("/save")
    public R<DnSyncFolder> save(@RequestBody DnSyncFolder folder) {
        if (folder.getParentId() == null) {
            folder.setParentId(0L);
        }
        if (folder.getId() != null) {
            folderMapper.updateById(folder);
        } else {
            folder.setCreatedAt(LocalDateTime.now());
            folderMapper.insert(folder);
        }
        return R.ok(folder);
    }

    @Operation(summary = "删除文件夹（其下任务 folder_id 回置 0）")
    @DeleteMapping("/{id}")
    @Transactional
    public R<String> delete(@PathVariable Long id) {
        List<DnSyncFolder> allFolders = folderMapper.selectList(null);
        Set<Long> deleteIds = new LinkedHashSet<>();
        collectDescendantIds(id, allFolders, deleteIds);
        if (deleteIds.isEmpty()) {
            deleteIds.add(id);
        }
        // 删除父文件夹时，子孙文件夹下的任务也移回根目录，避免任务悬挂到已删除目录。
        syncJobMapper.update(null, new UpdateWrapper<DnSyncJob>()
                .in("folder_id", deleteIds)
                .set("folder_id", 0L)
                .set("updated_at", LocalDateTime.now()));
        folderMapper.deleteBatchIds(deleteIds);
        return R.ok("删除成功");
    }

    private void collectDescendantIds(Long id, List<DnSyncFolder> allFolders, Set<Long> result) {
        if (id == null || result.contains(id)) {
            return;
        }
        result.add(id);
        if (allFolders == null) {
            return;
        }
        for (DnSyncFolder folder : allFolders) {
            if (folder != null && id.equals(folder.getParentId())) {
                collectDescendantIds(folder.getId(), allFolders, result);
            }
        }
    }
}
