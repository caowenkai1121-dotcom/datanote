package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectTagMapper;
import com.datanote.domain.project.mapper.DnProjectTagMappingMapper;
import com.datanote.domain.project.model.DnProjectTag;
import com.datanote.domain.project.model.DnProjectTagMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目标签：标签 CRUD + 项目打标 + 关联查询。 */
@Service
@RequiredArgsConstructor
public class ProjectTagService {

    private final DnProjectTagMapper tagMapper;
    private final DnProjectTagMappingMapper mappingMapper;

    public List<DnProjectTag> listTags() {
        return tagMapper.selectList(new LambdaQueryWrapper<DnProjectTag>().orderByAsc(DnProjectTag::getId));
    }

    public DnProjectTag createTag(String name, String color) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("标签名不能为空");
        name = name.trim();
        Long dup = tagMapper.selectCount(new LambdaQueryWrapper<DnProjectTag>().eq(DnProjectTag::getTagName, name));
        if (dup != null && dup > 0) throw new IllegalArgumentException("标签已存在: " + name);
        DnProjectTag t = new DnProjectTag();
        t.setTagName(name);
        t.setTagColor(color == null || color.trim().isEmpty() ? "#1677ff" : color.trim());
        t.setCreatedBy(ProjectService.currentUser());
        tagMapper.insert(t);
        return t;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteTag(Long tagId) {
        tagMapper.deleteById(tagId);
        mappingMapper.delete(new LambdaQueryWrapper<DnProjectTagMapping>().eq(DnProjectTagMapping::getTagId, tagId));
    }

    /** 所有项目-标签关联（前端一次拉取建 项目→标签 映射，避免 N+1）。 */
    public List<DnProjectTagMapping> allMappings() {
        return mappingMapper.selectList(null);
    }

    /** 覆盖设置某项目的标签集合（仅接受真实存在的标签，避免孤立关联）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void setProjectTags(Long projectId, List<Long> tagIds) {
        mappingMapper.delete(new LambdaQueryWrapper<DnProjectTagMapping>().eq(DnProjectTagMapping::getProjectId, projectId));
        if (tagIds == null) return;
        // 仅校验本次请求涉及的标签是否真实存在(按需批量查, 避免全表扫)
        java.util.Set<Long> wantIds = new java.util.LinkedHashSet<>();
        for (Long tid : tagIds) if (tid != null) wantIds.add(tid);
        if (wantIds.isEmpty()) return;
        java.util.Set<Long> existing = new java.util.HashSet<>();
        List<DnProjectTag> hitTags = tagMapper.selectBatchIds(wantIds);
        if (hitTags != null) for (DnProjectTag t : hitTags) {
            if (t != null) existing.add(t.getId());
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Long tid : tagIds) {
            if (tid == null || !existing.contains(tid) || !seen.add(tid)) continue;
            DnProjectTagMapping m = new DnProjectTagMapping();
            m.setProjectId(projectId);
            m.setTagId(tid);
            mappingMapper.insert(m);
        }
    }
}
