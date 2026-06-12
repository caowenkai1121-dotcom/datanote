package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.datanote.domain.project.mapper.DnProjectAssetMapper;
import com.datanote.domain.project.mapper.DnProjectTaskMapper;
import com.datanote.domain.project.model.DnProjectAsset;
import com.datanote.domain.project.model.DnProjectTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * N4: 平台资产(同步任务/脚本/质量规则/数据源)删除时联动清理项目侧引用——
 * 删 dn_project_asset 绑定 + 项目任务 ref 置空, 防僵尸行/悬空引用污染健康分与发布清单。
 * 由各域删除路径调用(同步任务/脚本/质量规则), 单向依赖无环。
 */
@Service
@RequiredArgsConstructor
public class ProjectAssetCleaner {

    private final DnProjectAssetMapper assetMapper;
    private final DnProjectTaskMapper taskMapper;

    public void onAssetDeleted(String assetType, Long assetId) {
        if (assetType == null || assetId == null) return;
        try {
            assetMapper.delete(new LambdaQueryWrapper<DnProjectAsset>()
                    .eq(DnProjectAsset::getAssetType, assetType).eq(DnProjectAsset::getAssetId, assetId));
            taskMapper.update(null, new LambdaUpdateWrapper<DnProjectTask>()
                    .eq(DnProjectTask::getRefType, assetType).eq(DnProjectTask::getRefId, assetId)
                    .set(DnProjectTask::getRefType, null).set(DnProjectTask::getRefId, null));
        } catch (Exception ignore) {
            // 清理失败不阻断资产删除主流程
        }
    }

    /** 删除前查绑定项目数(前端确认提示用) */
    public long boundProjectCount(String assetType, Long assetId) {
        Long n = assetMapper.selectCount(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getAssetType, assetType).eq(DnProjectAsset::getAssetId, assetId));
        return n == null ? 0 : n;
    }
}
