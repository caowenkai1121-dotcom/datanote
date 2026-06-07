package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.mapper.DnMdmXrefMapper;
import com.datanote.model.DnMdmGoldenRecord;
import com.datanote.model.DnMdmXref;
import com.datanote.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据交叉引用(XREF) Controller —— 黄金记录与源系统业务ID的映射维护，及按源ID反查黄金记录。
 */
@RestController
@RequestMapping("/api/mdm/xref")
@Tag(name = "主数据交叉引用", description = "黄金记录与源系统ID映射及反查")
@RequiredArgsConstructor
public class MdmXrefController {

    private final DnMdmXrefMapper xrefMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;

    @Operation(summary = "某黄金记录的源系统映射")
    @GetMapping("/list")
    public R<List<DnMdmXref>> list(@RequestParam Long goldenRecordId) {
        QueryWrapper<DnMdmXref> qw = new QueryWrapper<>();
        qw.eq("golden_record_id", goldenRecordId).orderByDesc("is_primary").orderByAsc("source_system");
        return R.ok(xrefMapper.selectList(qw));
    }

    @Operation(summary = "某实体下全部交叉引用（附黄金记录业务主键）")
    @GetMapping("/by-entity")
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
    @GetMapping("/stats")
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
    @PostMapping("/save")
    public R<DnMdmXref> save(@RequestBody DnMdmXref xref) {
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
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        xrefMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "按源系统ID反查黄金记录(XREF resolve)")
    @GetMapping("/resolve")
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
