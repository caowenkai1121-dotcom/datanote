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

import java.util.*;
import java.util.regex.Pattern;

/**
 * 主数据质量监控（Quality）Controller —— 校验黄金记录是否符合其实体属性约束。
 * 全程 on-the-fly：复用 golden + attribute schema，用 ObjectMapper 解析 data_json，
 * 逐条校验必填/枚举/唯一/数据类型格式，返回合规率与问题清单。无新建表。
 */
@RestController
@RequestMapping("/api/mdm/quality")
@Tag(name = "主数据-质量监控", description = "黄金记录对实体属性约束的合规性校验")
@RequiredArgsConstructor
public class MdmQualityController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmEntityMapper entityMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern P_INT = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern P_DECIMAL = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern P_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?$");

    @Operation(summary = "逐条质量校验（按实体）")
    @GetMapping("/check")
    public R<Map<String, Object>> check(@RequestParam Long entityId) {
        DnMdmEntity entity = entityMapper.selectById(entityId);
        if (entity == null) throw new ResourceNotFoundException("所属实体");
        List<DnMdmAttribute> attrs = loadAttrs(entityId);
        List<DnMdmGoldenRecord> records = loadCheckableRecords(entityId);

        // 唯一性预扫描：唯一属性 -> (值 -> 出现次数)
        Map<String, Map<String, Integer>> uniqCount = buildUniqueCount(attrs, records);

        List<Map<String, Object>> rows = new ArrayList<>();
        int compliant = 0;
        for (DnMdmGoldenRecord rec : records) {
            Map<String, Object> values = parseJson(rec.getDataJson());
            List<String> issues = collectIssues(attrs, values, uniqCount);
            boolean ok = issues.isEmpty();
            if (ok) compliant++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rec.getId());
            row.put("bizKey", rec.getBizKey());
            row.put("status", rec.getStatus());
            row.put("compliant", ok);
            row.put("issues", issues);
            row.put("issueCount", issues.size());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityId", entityId);
        data.put("entityName", entity.getEntityName());
        data.put("total", records.size());
        data.put("compliant", compliant);
        data.put("nonCompliant", records.size() - compliant);
        data.put("score", records.isEmpty() ? 100 : (int) Math.round(compliant * 100.0 / records.size()));
        data.put("records", rows);
        return R.ok(data);
    }

    @Operation(summary = "质量总览（合规率+问题分类计数）")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam Long entityId) {
        DnMdmEntity entity = entityMapper.selectById(entityId);
        if (entity == null) throw new ResourceNotFoundException("所属实体");
        List<DnMdmAttribute> attrs = loadAttrs(entityId);
        List<DnMdmGoldenRecord> records = loadCheckableRecords(entityId);
        Map<String, Map<String, Integer>> uniqCount = buildUniqueCount(attrs, records);

        int compliant = 0, missingRequired = 0, enumOut = 0, uniqueDup = 0, typeErr = 0;
        for (DnMdmGoldenRecord rec : records) {
            Map<String, Object> values = parseJson(rec.getDataJson());
            int[] cnt = countByCategory(attrs, values, uniqCount);
            missingRequired += cnt[0];
            enumOut += cnt[1];
            uniqueDup += cnt[2];
            typeErr += cnt[3];
            if (cnt[0] + cnt[1] + cnt[2] + cnt[3] == 0) compliant++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityId", entityId);
        data.put("entityName", entity.getEntityName());
        data.put("total", records.size());
        data.put("compliant", compliant);
        data.put("nonCompliant", records.size() - compliant);
        data.put("score", records.isEmpty() ? 100 : (int) Math.round(compliant * 100.0 / records.size()));
        data.put("missingRequired", missingRequired);
        data.put("enumOut", enumOut);
        data.put("uniqueDup", uniqueDup);
        data.put("typeErr", typeErr);
        return R.ok(data);
    }

    // ------- 校验核心 -------

    /** 收集某记录全部问题（人类可读文案）。 */
    private List<String> collectIssues(List<DnMdmAttribute> attrs, Map<String, Object> values,
                                       Map<String, Map<String, Integer>> uniqCount) {
        List<String> issues = new ArrayList<>();
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            String sv = v == null ? "" : String.valueOf(v).trim();
            boolean empty = sv.isEmpty();
            // 必填
            if (isOn(a.getRequired()) && empty) {
                issues.add("缺必填：" + a.getAttrName());
                continue; // 空值不再做后续格式校验
            }
            if (empty) continue;
            // 枚举越界
            if ("ENUM".equalsIgnoreCase(a.getDataType()) && !enumHit(a.getEnumValues(), sv)) {
                issues.add("枚举越界：" + a.getAttrName() + "=" + sv);
            }
            // 数据类型格式
            if (!typeOk(a.getDataType(), sv)) {
                issues.add("类型错误：" + a.getAttrName() + "(" + a.getDataType() + ")=" + sv);
            }
            // 唯一冲突
            if (isOn(a.getIsUnique())) {
                Map<String, Integer> m = uniqCount.get(a.getAttrCode());
                if (m != null && m.getOrDefault(sv, 0) > 1) {
                    issues.add("唯一冲突：" + a.getAttrName() + "=" + sv);
                }
            }
        }
        return issues;
    }

    /** 按问题分类计数：[缺必填, 枚举越界, 唯一冲突, 类型错误]。 */
    private int[] countByCategory(List<DnMdmAttribute> attrs, Map<String, Object> values,
                                  Map<String, Map<String, Integer>> uniqCount) {
        int missing = 0, enumOut = 0, uniqueDup = 0, typeErr = 0;
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            String sv = v == null ? "" : String.valueOf(v).trim();
            boolean empty = sv.isEmpty();
            if (isOn(a.getRequired()) && empty) { missing++; continue; }
            if (empty) continue;
            if ("ENUM".equalsIgnoreCase(a.getDataType()) && !enumHit(a.getEnumValues(), sv)) enumOut++;
            if (!typeOk(a.getDataType(), sv)) typeErr++;
            if (isOn(a.getIsUnique())) {
                Map<String, Integer> m = uniqCount.get(a.getAttrCode());
                if (m != null && m.getOrDefault(sv, 0) > 1) uniqueDup++;
            }
        }
        return new int[]{missing, enumOut, uniqueDup, typeErr};
    }

    /** 唯一属性的全实体值计数（用于检测跨记录重复）。 */
    private Map<String, Map<String, Integer>> buildUniqueCount(List<DnMdmAttribute> attrs, List<DnMdmGoldenRecord> records) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (DnMdmAttribute a : attrs) {
            if (!isOn(a.getIsUnique())) continue;
            Map<String, Integer> m = new HashMap<>();
            for (DnMdmGoldenRecord rec : records) {
                Map<String, Object> values = parseJson(rec.getDataJson());
                Object v = values.get(a.getAttrCode());
                String sv = v == null ? "" : String.valueOf(v).trim();
                if (sv.isEmpty()) continue;
                m.merge(sv, 1, Integer::sum);
            }
            result.put(a.getAttrCode(), m);
        }
        return result;
    }

    private boolean enumHit(String enumValues, String val) {
        if (enumValues == null || enumValues.trim().isEmpty()) return true; // 未配候选值则不校验
        for (String opt : enumValues.split(",")) {
            if (opt.trim().equals(val)) return true;
        }
        return false;
    }

    /** 数据类型格式校验（仅对有明确格式要求的类型；STRING/REFERENCE/未知类型放行）。 */
    private boolean typeOk(String dataType, String val) {
        if (dataType == null) return true;
        switch (dataType.toUpperCase()) {
            case "INT":     return P_INT.matcher(val).matches();
            case "DECIMAL": return P_DECIMAL.matcher(val).matches();
            case "DATE":    return P_DATE.matcher(val).matches();
            case "BOOLEAN": return val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false")
                                || val.equals("0") || val.equals("1")
                                || val.equals("是") || val.equals("否");
            default:        return true;
        }
    }

    private boolean isOn(Integer flag) {
        return flag != null && flag == 1;
    }

    // ------- 数据加载/工具 -------

    /** 参与校验的记录：active + draft（inactive 已停用不计入合规率）。 */
    private List<DnMdmGoldenRecord> loadCheckableRecords(Long entityId) {
        QueryWrapper<DnMdmGoldenRecord> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).in("status", Arrays.asList("active", "draft"));
        qw.orderByDesc("updated_at").last("LIMIT 1000");
        return goldenMapper.selectList(qw);
    }

    private List<DnMdmAttribute> loadAttrs(Long entityId) {
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        return attributeMapper.selectList(qw);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("属性值格式错误（非合法 JSON）");
        }
    }
}
