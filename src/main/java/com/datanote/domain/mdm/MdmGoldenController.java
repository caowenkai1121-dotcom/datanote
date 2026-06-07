package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmAttributeMapper;
import com.datanote.mapper.DnMdmEntityMapper;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.common.model.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据黄金记录 Controller —— 基于实体属性 schema 的动态记录 CRUD + 状态流转。
 */
@RestController
@RequestMapping("/api/mdm/golden")
@Tag(name = "主数据黄金记录", description = "黄金记录的增删改查与发布")
@RequiredArgsConstructor
public class MdmGoldenController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmEntityMapper entityMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "黄金记录列表（按实体）")
    @GetMapping("/list")
    public R<List<DnMdmGoldenRecord>> list(@RequestParam Long entityId,
                                           @RequestParam(required = false) String status) {
        QueryWrapper<DnMdmGoldenRecord> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("updated_at").last("LIMIT 500");
        return R.ok(goldenMapper.selectList(qw));
    }

    @Operation(summary = "黄金记录详情")
    @GetMapping("/{id}")
    public R<DnMdmGoldenRecord> get(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        return R.ok(rec);
    }

    @Operation(summary = "各状态统计（按实体）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam Long entityId) {
        List<DnMdmGoldenRecord> all = list(entityId, null).getData();
        if (all == null) all = new java.util.ArrayList<>();
        Map<String, Object> data = new HashMap<>();
        long active = all.stream().filter(r -> "active".equals(r.getStatus())).count();
        long draft = all.stream().filter(r -> "draft".equals(r.getStatus())).count();
        long inactive = all.stream().filter(r -> "inactive".equals(r.getStatus())).count();
        data.put("total", all.size());
        data.put("active", active);
        data.put("draft", draft);
        data.put("inactive", inactive);
        return R.ok(data);
    }

    @Operation(summary = "保存黄金记录（含必填属性校验）")
    @PostMapping("/save")
    public R<DnMdmGoldenRecord> save(@RequestBody DnMdmGoldenRecord rec) {
        if (rec.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        DnMdmEntity entity = entityMapper.selectById(rec.getEntityId());
        if (entity == null) throw new ResourceNotFoundException("所属实体");

        // 解析属性值 JSON
        Map<String, Object> values = parseJson(rec.getDataJson());

        // 加载实体属性，做必填校验 + 计算业务主键
        List<DnMdmAttribute> attrs = loadAttrs(rec.getEntityId());
        String bizKey = null;
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            boolean empty = (v == null || String.valueOf(v).trim().isEmpty());
            if (a.getRequired() != null && a.getRequired() == 1 && empty) {
                throw new BusinessException("必填属性未填写：" + a.getAttrName());
            }
            // 业务主键优先取关键字段，其次唯一字段的首个非空值
            if (bizKey == null && !empty && ((a.getIsKey() != null && a.getIsKey() == 1)
                    || (a.getIsUnique() != null && a.getIsUnique() == 1))) {
                bizKey = String.valueOf(v);
            }
        }
        if (bizKey == null) {
            // 退化：取第一个非空属性值
            for (DnMdmAttribute a : attrs) {
                Object v = values.get(a.getAttrCode());
                if (v != null && !String.valueOf(v).trim().isEmpty()) { bizKey = String.valueOf(v); break; }
            }
        }
        rec.setBizKey(bizKey != null ? bizKey : ("记录-" + System.currentTimeMillis()));
        if (rec.getStatus() == null || rec.getStatus().isEmpty()) rec.setStatus("draft");

        if (rec.getId() != null) {
            DnMdmGoldenRecord old = goldenMapper.selectById(rec.getId());
            rec.setVersion((old != null && old.getVersion() != null ? old.getVersion() : 1) + 1);
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.updateById(rec);
        } else {
            rec.setVersion(1);
            rec.setCreatedAt(LocalDateTime.now());
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.insert(rec);
        }
        return R.ok(rec);
    }

    @Operation(summary = "发布黄金记录（草稿→生效）")
    @PostMapping("/{id}/publish")
    public R<DnMdmGoldenRecord> publish(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        rec.setStatus("active");
        rec.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(rec);
        return R.ok(rec);
    }

    @Operation(summary = "停用黄金记录")
    @PostMapping("/{id}/deactivate")
    public R<DnMdmGoldenRecord> deactivate(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        rec.setStatus("inactive");
        rec.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(rec);
        return R.ok(rec);
    }

    @Operation(summary = "删除黄金记录")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        goldenMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ------- 工具 -------
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("属性值格式错误（非合法 JSON）");
        }
    }

    private List<DnMdmAttribute> loadAttrs(Long entityId) {
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        return attributeMapper.selectList(qw);
    }
}
