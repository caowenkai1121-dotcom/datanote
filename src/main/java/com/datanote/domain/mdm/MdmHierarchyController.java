package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmHierarchyMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmHierarchy;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据层级管理(Hierarchy) Controller —— 维护黄金记录间的树形层级
 * (组织架构/地区/产品分类),并可构建树形结构返回。
 */
@RestController
@RequestMapping("/api/mdm/hierarchy")
@Tag(name = "主数据层级管理", description = "黄金记录间的树形层级关系维护与树构建")
@RequiredArgsConstructor
public class MdmHierarchyController {

    private final DnMdmHierarchyMapper hierarchyMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;

    @Operation(summary = "层级关系清单（按实体/类型，附父子业务主键）")
    @GetMapping("/list")
    public R<List<DnMdmHierarchy>> list(@RequestParam Long entityId,
                                        @RequestParam(required = false) String hierarchyType) {
        QueryWrapper<DnMdmHierarchy> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId);
        if (hierarchyType != null && !hierarchyType.isEmpty()) qw.eq("hierarchy_type", hierarchyType);
        qw.orderByAsc("sort_order").orderByAsc("id");
        List<DnMdmHierarchy> rows = hierarchyMapper.selectList(qw);
        for (DnMdmHierarchy h : rows) {
            h.setParentBizKey(bizKeyOf(h.getParentRecordId()));
            h.setChildBizKey(bizKeyOf(h.getChildRecordId()));
        }
        return R.ok(rows);
    }

    @Operation(summary = "保存层级关系（校验父/子黄金记录存在+不自引用）")
    @PostMapping("/save")
    public R<DnMdmHierarchy> save(@RequestBody DnMdmHierarchy h) {
        if (h.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (h.getHierarchyType() == null || h.getHierarchyType().trim().isEmpty()) {
            throw new BusinessException("层级类型不能为空");
        }
        if (h.getChildRecordId() == null) throw new BusinessException("请指定子黄金记录");
        h.setHierarchyType(h.getHierarchyType().trim());

        // 子黄金记录必须存在且属于该实体
        DnMdmGoldenRecord child = goldenMapper.selectById(h.getChildRecordId());
        if (child == null) throw new ResourceNotFoundException("子黄金记录");
        if (!Objects.equals(child.getEntityId(), h.getEntityId())) {
            throw new BusinessException("子黄金记录不属于所选实体");
        }
        // 父黄金记录可空（根节点）；若指定则校验存在、属于该实体、且不自引用
        if (h.getParentRecordId() != null) {
            if (Objects.equals(h.getParentRecordId(), h.getChildRecordId())) {
                throw new BusinessException("父子不能为同一黄金记录（不可自引用）");
            }
            DnMdmGoldenRecord parent = goldenMapper.selectById(h.getParentRecordId());
            if (parent == null) throw new ResourceNotFoundException("父黄金记录");
            if (!Objects.equals(parent.getEntityId(), h.getEntityId())) {
                throw new BusinessException("父黄金记录不属于所选实体");
            }
        }

        // 同实体同类型下，子节点只能挂在一个父节点（避免重复/多父）
        QueryWrapper<DnMdmHierarchy> dupQw = new QueryWrapper<>();
        dupQw.eq("entity_id", h.getEntityId())
                .eq("hierarchy_type", h.getHierarchyType())
                .eq("child_record_id", h.getChildRecordId());
        if (h.getId() != null) dupQw.ne("id", h.getId());
        DnMdmHierarchy dup = hierarchyMapper.selectOne(dupQw);
        if (dup != null) {
            throw new BusinessException("该子黄金记录在此层级类型下已存在关系");
        }

        if (h.getSortOrder() == null) h.setSortOrder(0);
        if (h.getId() != null) {
            h.setUpdatedAt(LocalDateTime.now());
            hierarchyMapper.updateById(h);
        } else {
            h.setCreatedAt(LocalDateTime.now());
            h.setUpdatedAt(LocalDateTime.now());
            hierarchyMapper.insert(h);
        }
        return R.ok(h);
    }

    @Operation(summary = "删除层级关系")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        hierarchyMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "构建树形结构（按实体/类型）")
    @GetMapping("/tree")
    public R<List<DnMdmHierarchy>> tree(@RequestParam Long entityId,
                                        @RequestParam(required = false) String hierarchyType) {
        List<DnMdmHierarchy> rows = list(entityId, hierarchyType).getData();
        if (rows == null) rows = new ArrayList<>();

        // 以 childRecordId 为节点键，构建父→子映射
        Map<Long, DnMdmHierarchy> nodeByChild = new LinkedHashMap<>();
        for (DnMdmHierarchy h : rows) {
            h.setBizKey(h.getChildBizKey());
            h.setChildren(new ArrayList<>());
            nodeByChild.put(h.getChildRecordId(), h);
        }
        List<DnMdmHierarchy> roots = new ArrayList<>();
        for (DnMdmHierarchy h : rows) {
            Long pid = h.getParentRecordId();
            DnMdmHierarchy parent = (pid != null) ? nodeByChild.get(pid) : null;
            // 父为空，或父节点本身未作为子节点出现在此层级中 → 视为根
            if (parent != null) {
                parent.getChildren().add(h);
            } else {
                roots.add(h);
            }
        }
        return R.ok(roots);
    }

    // ------- 工具 -------
    private String bizKeyOf(Long recordId) {
        if (recordId == null) return null;
        DnMdmGoldenRecord g = goldenMapper.selectById(recordId);
        return g != null ? g.getBizKey() : null;
    }
}
