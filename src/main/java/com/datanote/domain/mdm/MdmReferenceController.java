package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmReferenceMapper;
import com.datanote.domain.mdm.model.DnMdmReference;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考数据/码表（Reference Data）Controller —— 系统级枚举与码表（国家/地区/行业分类等），支持树形 parent_code。
 */
@RestController
@RequestMapping("/api/mdm/refdata")
@Tag(name = "参考数据/码表", description = "系统级枚举与码表管理，支持树形结构")
@RequiredArgsConstructor
public class MdmReferenceController {

    private final DnMdmReferenceMapper referenceMapper;

    @Operation(summary = "码表类别列表（含各类别条目数）")
    @GetMapping("/categories")
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
    @GetMapping("/list")
    public R<List<DnMdmReference>> list(@RequestParam String category) {
        if (category == null || category.trim().isEmpty()) {
            throw new BusinessException("码表类别不能为空");
        }
        QueryWrapper<DnMdmReference> qw = new QueryWrapper<>();
        qw.eq("category", category.trim()).orderByAsc("sort_order").orderByAsc("id");
        return R.ok(referenceMapper.selectList(qw));
    }

    @Operation(summary = "保存码值（category+code 唯一校验）")
    @PostMapping("/save")
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
    @DeleteMapping("/{id}")
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
}
