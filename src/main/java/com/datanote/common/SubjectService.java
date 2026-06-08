package com.datanote.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnSubjectMapper;
import com.datanote.domain.metadata.model.DnSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主题域服务 — 树形结构构建与增删操作
 */
@Service
@RequiredArgsConstructor
public class SubjectService {

    private final DnSubjectMapper subjectMapper;

    /**
     * 获取所有主题域并构建父子树
     *
     * @return 树形结构列表 [{id, name, parentId, layer, sortOrder, children:[...]}]
     */
    public List<Map<String, Object>> listTree() {
        QueryWrapper<DnSubject> qw = new QueryWrapper<>();
        qw.orderByAsc("sort_order", "id");
        List<DnSubject> subjects = subjectMapper.selectList(qw);
        if (subjects == null) subjects = new ArrayList<>();
        return buildTree(subjects, null);
    }

    /**
     * 创建主题域
     *
     * @param subject 主题域实体
     * @return 插入后的实体（含自增 ID）
     */
    public DnSubject create(DnSubject subject) {
        if (subject == null) throw new IllegalArgumentException("主题域不能为空");
        subject.setCreatedAt(LocalDateTime.now());
        subjectMapper.insert(subject);
        return subject;
    }

    /**
     * 删除主题域（级联删除子节点）
     *
     * @param id 主题域 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) return;
        deleteRecursive(id);
    }

    /** 递归删除整棵子树后删自身（原仅删一层子节点会致孙节点孤立）。 */
    private void deleteRecursive(Long id) {
        QueryWrapper<DnSubject> childQuery = new QueryWrapper<>();
        childQuery.eq("parent_id", id);
        List<DnSubject> children = subjectMapper.selectList(childQuery);
        if (children != null) {
            for (DnSubject c : children) {
                if (c != null && c.getId() != null) deleteRecursive(c.getId());
            }
        }
        subjectMapper.deleteById(id);
    }

    /**
     * 递归构建树形结构
     */
    private List<Map<String, Object>> buildTree(List<DnSubject> subjects, Long parentId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DnSubject s : subjects) {
            if (s == null) continue;
            boolean match = (parentId == null && s.getParentId() == null)
                    || (parentId != null && parentId.equals(s.getParentId()));
            if (match) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", s.getId());
                node.put("name", s.getName());
                node.put("parentId", s.getParentId());
                node.put("layer", s.getLayer());
                node.put("sortOrder", s.getSortOrder());
                node.put("children", buildTree(subjects, s.getId()));
                nodes.add(node);
            }
        }
        return nodes;
    }
}
