package com.datanote.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnSubjectMapper;
import com.datanote.domain.metadata.model.DnSubject;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 主题域管理 Controller
 */
@RestController
@RequestMapping("/api/subject")
@RequiredArgsConstructor
@Tag(name = "主题域管理", description = "主题域的增删改查与树形结构")
public class SubjectController {

    private final DnSubjectMapper subjectMapper;
    private final SubjectService subjectService;

    /**
     * 获取所有主题域（平铺列表）
     */
    @Operation(summary = "获取主题域列表")
    @GetMapping("/list")
    public R<List<DnSubject>> list() {
        QueryWrapper<DnSubject> qw = new QueryWrapper<>();
        qw.orderByAsc("sort_order", "id");
        return R.ok(subjectMapper.selectList(qw));
    }

    /**
     * 获取主题域树形结构
     */
    @Operation(summary = "获取主题域树")
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        QueryWrapper<DnSubject> qw = new QueryWrapper<>();
        qw.orderByAsc("sort_order", "id");
        List<DnSubject> subjects = subjectMapper.selectList(qw);
        return R.ok(buildTree(subjects, null));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTree(List<DnSubject> subjects, Long parentId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DnSubject s : subjects) {
            boolean match = (parentId == null && s.getParentId() == null)
                    || (parentId != null && parentId.equals(s.getParentId()));
            if (match) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", s.getId());
                node.put("name", s.getName());
                node.put("layer", s.getLayer());
                node.put("sortOrder", s.getSortOrder());
                node.put("children", buildTree(subjects, s.getId()));
                nodes.add(node);
            }
        }
        return nodes;
    }

    /**
     * 创建主题域
     */
    @Operation(summary = "创建主题域")
    @PostMapping
    public R<DnSubject> create(@RequestBody DnSubject subject) {
        // 走 service 以自动计算 L1-L5 层级(按父节点 level+1, 超 5 级拒绝)
        return R.ok(subjectService.create(subject));
    }

    /**
     * 更新主题域
     */
    @Operation(summary = "更新主题域")
    @PutMapping("/{id}")
    public R<DnSubject> update(@PathVariable Long id, @RequestBody DnSubject subject) {
        subject.setId(id);
        subjectMapper.updateById(subject);
        return R.ok(subject);
    }

    /**
     * 删除主题域
     */
    @Operation(summary = "删除主题域")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        // 走 service 级联删除子主题，避免留下孤儿节点
        subjectService.delete(id);
        return R.ok("删除成功");
    }
}
