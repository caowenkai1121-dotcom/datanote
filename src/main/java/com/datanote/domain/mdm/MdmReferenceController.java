package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmHierarchyMapper;
import com.datanote.domain.mdm.mapper.DnMdmReferenceMapper;
import com.datanote.domain.mdm.mapper.DnMdmXrefMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmHierarchy;
import com.datanote.domain.mdm.model.DnMdmReference;
import com.datanote.domain.mdm.model.DnMdmXref;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * MdmReferenceController —— 合并自原 3 个 controller(行为不变, 路径保留)。
 */
@RestController
@Tag(name = "主数据-参考与关系", description = "参考数据 + 层级 + 交叉引用")
@RequiredArgsConstructor
public class MdmReferenceController {

    private final DnMdmReferenceMapper referenceMapper;
    private final DnMdmHierarchyMapper hierarchyMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmXrefMapper xrefMapper;

    // ===== 源自 MdmReferenceController.java =====
    @Operation(summary = "码表类别列表（含各类别条目数）")
    @GetMapping("/api/mdm/refdata/categories")
    public R<List<Map<String, Object>>> categories() {
        List<DnMdmReference> all = referenceMapper.selectList(new QueryWrapper<DnMdmReference>().orderByAsc("category"));
        // 按类别聚合：条目数 + 启用数
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (DnMdmReference r : all) {
            String cat = r.getCategory() == null ? "" : r.getCategory();
            int[] cnt = agg.computeIfAbsent(cat, k -> new int[2]);
            cnt[0]++;
            if (r.getStatus() != null && r.getStatus() == 1) cnt[1]++;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", e.getKey());
            m.put("itemCount", e.getValue()[0]);
            m.put("enabledCount", e.getValue()[1]);
            result.add(m);
        }
        return R.ok(result);
    }

    @Operation(summary = "某类别码值列表（按 sort_order）")
    @GetMapping("/api/mdm/refdata/list")
    public R<List<DnMdmReference>> list(@RequestParam String category) {
        if (category == null || category.trim().isEmpty()) {
            throw new BusinessException("码表类别不能为空");
        }
        QueryWrapper<DnMdmReference> qw = new QueryWrapper<>();
        qw.eq("category", category.trim()).orderByAsc("sort_order").orderByAsc("id");
        return R.ok(referenceMapper.selectList(qw));
    }

    @Operation(summary = "保存码值（category+code 唯一校验）")
    @PostMapping("/api/mdm/refdata/save")
    public R<DnMdmReference> save(@RequestBody DnMdmReference ref) {
        if (ref.getCategory() == null || ref.getCategory().trim().isEmpty()) {
            throw new BusinessException("码表类别不能为空");
        }
        if (ref.getCode() == null || ref.getCode().trim().isEmpty()) {
            throw new BusinessException("码值不能为空");
        }
        if (ref.getName() == null || ref.getName().trim().isEmpty()) {
            throw new BusinessException("码值名称不能为空");
        }
        ref.setCategory(ref.getCategory().trim());
        ref.setCode(ref.getCode().trim());
        // 同类别下码值唯一（排除自身）
        QueryWrapper<DnMdmReference> qw = new QueryWrapper<>();
        qw.eq("category", ref.getCategory()).eq("code", ref.getCode());
        if (ref.getId() != null) qw.ne("id", ref.getId());
        if (referenceMapper.selectCount(qw) > 0) {
            throw new BusinessException("该类别下码值已存在：" + ref.getCode());
        }
        // 父级码值若填写，需同类别下存在且不能指向自身
        if (ref.getParentCode() != null && !ref.getParentCode().trim().isEmpty()) {
            ref.setParentCode(ref.getParentCode().trim());
            if (ref.getParentCode().equals(ref.getCode())) {
                throw new BusinessException("父级码值不能指向自身");
            }
            QueryWrapper<DnMdmReference> pq = new QueryWrapper<>();
            pq.eq("category", ref.getCategory()).eq("code", ref.getParentCode());
            if (referenceMapper.selectCount(pq) == 0) {
                throw new BusinessException("父级码值不存在：" + ref.getParentCode());
            }
        } else {
            ref.setParentCode(null);
        }
        if (ref.getStatus() == null) ref.setStatus(1);
        if (ref.getSortOrder() == null) ref.setSortOrder(0);
        if (ref.getId() != null) {
            ref.setUpdatedAt(LocalDateTime.now());
            referenceMapper.updateById(ref);
        } else {
            ref.setCreatedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            referenceMapper.insert(ref);
        }
        return R.ok(ref);
    }

    @Operation(summary = "删除码值")
    @DeleteMapping("/api/mdm/refdata/{id}")
    public R<String> delete(@PathVariable Long id) {
        DnMdmReference ref = referenceMapper.selectById(id);
        if (ref == null) throw new ResourceNotFoundException("码值");
        // 存在子级码值时不允许删除
        QueryWrapper<DnMdmReference> qw = new QueryWrapper<>();
        qw.eq("category", ref.getCategory()).eq("parent_code", ref.getCode());
        if (referenceMapper.selectCount(qw) > 0) {
            throw new BusinessException("该码值存在子级，请先删除其下级码值");
        }
        referenceMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ===== 源自 MdmHierarchyController.java =====
    @Operation(summary = "层级关系清单（按实体/类型，附父子业务主键）")
    @GetMapping("/api/mdm/hierarchy/list")
    public R<List<DnMdmHierarchy>> hierarchyList(@RequestParam Long entityId,
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
    @PostMapping("/api/mdm/hierarchy/save")
    public R<DnMdmHierarchy> hierarchySave(@RequestBody DnMdmHierarchy h) {
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
    @DeleteMapping("/api/mdm/hierarchy/{id}")
    public R<String> hierarchyDelete(@PathVariable Long id) {
        hierarchyMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "构建树形结构（按实体/类型）")
    @GetMapping("/api/mdm/hierarchy/tree")
    public R<List<DnMdmHierarchy>> tree(@RequestParam Long entityId,
                                        @RequestParam(required = false) String hierarchyType) {
        List<DnMdmHierarchy> rows = hierarchyList(entityId, hierarchyType).getData();
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

    // ===== 源自 MdmXrefController.java =====
    @Operation(summary = "某黄金记录的源系统映射")
    @GetMapping("/api/mdm/xref/list")
    public R<List<DnMdmXref>> xrefList(@RequestParam Long goldenRecordId) {
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("golden_record_id", goldenRecordId).orderByDesc("is_primary").orderByAsc("source_system");
        return R.ok(xrefMapper.selectList(qw));
    }

    @Operation(summary = "某实体下全部交叉引用（附黄金记录业务主键）")
    @GetMapping("/api/mdm/xref/by-entity")
    public R<List<DnMdmXref>> byEntity(@RequestParam Long entityId) {
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("source_system");
        List<DnMdmXref> xrefs = xrefMapper.selectList(qw);
        for (DnMdmXref x : xrefs) {
            DnMdmGoldenRecord g = goldenMapper.selectById(x.getGoldenRecordId());
            if (g != null) x.setBizKey(g.getBizKey());
        }
        return R.ok(xrefs);
    }

    @Operation(summary = "交叉引用统计（按实体）")
    @GetMapping("/api/mdm/xref/stats")
    public R<Map<String, Object>> stats(@RequestParam Long entityId) {
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId);
        List<DnMdmXref> xrefs = xrefMapper.selectList(qw);
        Map<String, Object> data = new HashMap<>();
        data.put("xrefCount", xrefs.size());
        Set<String> systems = new TreeSet<>();
        Set<Long> mappedGolden = new HashSet<>();
        for (DnMdmXref x : xrefs) {
            if (x.getSourceSystem() != null) systems.add(x.getSourceSystem());
            mappedGolden.add(x.getGoldenRecordId());
        }
        data.put("sourceSystemCount", systems.size());
        data.put("sourceSystems", new ArrayList<>(systems));
        data.put("mappedGoldenCount", mappedGolden.size());
        return R.ok(data);
    }

    @Operation(summary = "保存交叉引用（源系统+源ID 全局唯一）")
    @PostMapping("/api/mdm/xref/save")
    public R<DnMdmXref> xrefSave(@RequestBody DnMdmXref xref) {
        if (xref.getGoldenRecordId() == null) throw new BusinessException("请指定黄金记录");
        if (xref.getSourceSystem() == null || xref.getSourceSystem().trim().isEmpty()) throw new BusinessException("源系统不能为空");
        if (xref.getSourceId() == null || xref.getSourceId().trim().isEmpty()) throw new BusinessException("源系统业务ID不能为空");
        DnMdmGoldenRecord g = goldenMapper.selectById(xref.getGoldenRecordId());
        if (g == null) throw new ResourceNotFoundException("黄金记录");
        xref.setSourceSystem(xref.getSourceSystem().trim());
        xref.setSourceId(xref.getSourceId().trim());
        xref.setEntityId(g.getEntityId());
        // 源系统+源ID 全局唯一（一个源记录只能映射到一个黄金记录）
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("source_system", xref.getSourceSystem()).eq("source_id", xref.getSourceId());
        if (xref.getId() != null) qw.ne("id", xref.getId());
        DnMdmXref dup = xrefMapper.selectOne(qw);
        if (dup != null) {
            throw new BusinessException("源标识 " + xref.getSourceSystem() + ":" + xref.getSourceId()
                    + " 已映射到其它黄金记录(#" + dup.getGoldenRecordId() + ")");
        }
        if (xref.getIsPrimary() == null) xref.setIsPrimary(0);
        if (xref.getId() != null) {
            xref.setUpdatedAt(LocalDateTime.now());
            xrefMapper.updateById(xref);
        } else {
            xref.setCreatedAt(LocalDateTime.now());
            xref.setUpdatedAt(LocalDateTime.now());
            xrefMapper.insert(xref);
        }
        return R.ok(xref);
    }

    @Operation(summary = "删除交叉引用")
    @DeleteMapping("/api/mdm/xref/{id}")
    public R<String> xrefDelete(@PathVariable Long id) {
        xrefMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "按源系统ID反查黄金记录(XREF resolve)")
    @GetMapping("/api/mdm/xref/resolve")
    public R<Map<String, Object>> resolve(@RequestParam String sourceSystem, @RequestParam String sourceId) {
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("source_system", sourceSystem.trim()).eq("source_id", sourceId.trim());
        DnMdmXref x = xrefMapper.selectOne(qw);
        Map<String, Object> data = new HashMap<>();
        if (x == null) {
            data.put("found", false);
            return R.ok(data);
        }
        data.put("found", true);
        data.put("goldenRecordId", x.getGoldenRecordId());
        DnMdmGoldenRecord g = goldenMapper.selectById(x.getGoldenRecordId());
        if (g != null) {
            data.put("bizKey", g.getBizKey());
            data.put("status", g.getStatus());
            data.put("dataJson", g.getDataJson());
        }
        return R.ok(data);
    }
}
