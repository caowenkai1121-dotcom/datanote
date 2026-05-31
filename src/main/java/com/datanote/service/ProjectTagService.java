package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectTagMapper;
import com.datanote.mapper.DnProjectTagMappingMapper;
import com.datanote.model.DnProjectTag;
import com.datanote.model.DnProjectTagMapping;
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

    public void deleteTag(Long tagId) {
        tagMapper.deleteById(tagId);
        mappingMapper.delete(new LambdaQueryWrapper<DnProjectTagMapping>().eq(DnProjectTagMapping::getTagId, tagId));
    }

    /** 所有项目-标签关联（前端一次拉取建 项目→标签 映射，避免 N+1）。 */
    public List<DnProjectTagMapping> allMappings() {
        return mappingMapper.selectList(null);
    }

    /** 覆盖设置某项目的标签集合。 */
    public void setProjectTags(Long projectId, List<Long> tagIds) {
        mappingMapper.delete(new LambdaQueryWrapper<DnProjectTagMapping>().eq(DnProjectTagMapping::getProjectId, projectId));
        if (tagIds == null) return;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Long tid : tagIds) {
            if (tid == null || !seen.add(tid)) continue;
            DnProjectTagMapping m = new DnProjectTagMapping();
            m.setProjectId(projectId);
            m.setTagId(tid);
            mappingMapper.insert(m);
        }
    }
}
