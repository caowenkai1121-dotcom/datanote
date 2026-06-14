package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmPublishLogMapper;
import com.datanote.domain.mdm.mapper.DnMdmSubscriptionMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmPublishLog;
import com.datanote.domain.mdm.model.DnMdmSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * MdmGovernanceController —— 合并自原 2 个 controller(行为不变, 路径保留)。
 */
@RestController
@Tag(name = "主数据-治理", description = "数据质量 + 发布订阅分发")
@RequiredArgsConstructor
public class MdmGovernanceController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmSubscriptionMapper subscriptionMapper;
    private final DnMdmPublishLogMapper publishLogMapper;
    private final MdmPublishService mdmPublishService;   // R32 发布扇出(与黄金记录自动发布共用)

    // ===== 源自 MdmQualityController.java =====
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern P_INT = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern P_DECIMAL = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern P_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?$");

    @Operation(summary = "逐条质量校验（按实体）")
    @GetMapping("/api/mdm/quality/check")
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
    @GetMapping("/api/mdm/quality/overview")
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

    // ===== 源自 MdmPubsubController.java =====
    @Operation(summary = "订阅列表（按实体筛选，附实体名称）")
    @GetMapping("/api/mdm/pubsub/subscriptions")
    public R<List<DnMdmSubscription>> subscriptions(@RequestParam(required = false) Long entityId) {
        QueryWrapper<DnMdmSubscription> qw = new QueryWrapper<>();
        if (entityId != null) qw.eq("entity_id", entityId);
        qw.orderByDesc("updated_at").last("LIMIT 500");
        List<DnMdmSubscription> rows = subscriptionMapper.selectList(qw);
        Set<Long> entityIds = new HashSet<>();
        for (DnMdmSubscription s : rows) {
            if (s.getEntityId() != null) entityIds.add(s.getEntityId());
        }
        Map<Long, String> entityNameMap = new HashMap<>();
        if (!entityIds.isEmpty()) {
            List<DnMdmEntity> _es = entityMapper.selectBatchIds(entityIds);
            if (_es != null) for (DnMdmEntity e : _es) { // selectList 理论可返回 null
                entityNameMap.put(e.getId(), e.getEntityName());
            }
        }
        for (DnMdmSubscription s : rows) {
            if (s.getEntityId() != null) {
                String name = entityNameMap.get(s.getEntityId());
                if (name != null) s.setEntityName(name);
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "保存订阅（订阅方+实体+变更类型+推送地址）")
    @PostMapping("/api/mdm/pubsub/subscription/save")
    public R<DnMdmSubscription> saveSubscription(@RequestBody DnMdmSubscription sub) {
        if (sub.getSubscriberSystem() == null || sub.getSubscriberSystem().trim().isEmpty()) {
            throw new BusinessException("订阅方系统不能为空");
        }
        if (sub.getEntityId() == null) throw new BusinessException("请先选择订阅的实体");
        if (entityMapper.selectById(sub.getEntityId()) == null) throw new ResourceNotFoundException("订阅的实体");
        String types = mdmPublishService.normalizeChangeTypes(sub.getChangeTypes());
        if (types.isEmpty()) throw new BusinessException("请至少订阅一种变更类型(create/update/delete)");
        if (sub.getEndpoint() == null || sub.getEndpoint().trim().isEmpty()) {
            throw new BusinessException("推送地址不能为空");
        }
        sub.setSubscriberSystem(sub.getSubscriberSystem().trim());
        sub.setChangeTypes(types);
        sub.setEndpoint(sub.getEndpoint().trim());
        if (sub.getStatus() == null) sub.setStatus(1);
        if (sub.getDescription() != null) sub.setDescription(sub.getDescription().trim());
        if (sub.getId() != null) {
            sub.setUpdatedAt(LocalDateTime.now());
            subscriptionMapper.updateById(sub);
        } else {
            sub.setCreatedAt(LocalDateTime.now());
            sub.setUpdatedAt(LocalDateTime.now());
            subscriptionMapper.insert(sub);
        }
        return R.ok(sub);
    }

    @Operation(summary = "删除订阅")
    @DeleteMapping("/api/mdm/pubsub/subscription/{id}")
    public R<String> deleteSubscription(@PathVariable Long id) {
        subscriptionMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "发布日志（按订阅筛选，附订阅方系统）")
    @GetMapping("/api/mdm/pubsub/logs")
    public R<List<DnMdmPublishLog>> logs(@RequestParam(required = false) Long subscriptionId) {
        QueryWrapper<DnMdmPublishLog> qw = new QueryWrapper<>();
        if (subscriptionId != null) qw.eq("subscription_id", subscriptionId);
        qw.orderByDesc("published_at").last("LIMIT 500");
        List<DnMdmPublishLog> rows = publishLogMapper.selectList(qw);
        Set<Long> subIds = new HashSet<>();
        for (DnMdmPublishLog l : rows) {
            if (l.getSubscriptionId() != null) subIds.add(l.getSubscriptionId());
        }
        Map<Long, String> subSystemMap = new HashMap<>();
        if (!subIds.isEmpty()) {
            List<DnMdmSubscription> _ss = subscriptionMapper.selectBatchIds(subIds);
            if (_ss != null) for (DnMdmSubscription s : _ss) { // selectList 理论可返回 null
                subSystemMap.put(s.getId(), s.getSubscriberSystem());
            }
        }
        for (DnMdmPublishLog l : rows) {
            if (l.getSubscriptionId() != null) {
                String name = subSystemMap.get(l.getSubscriptionId());
                if (name != null) l.setSubscriberSystem(name);
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "模拟发布黄金记录变更（对匹配订阅写发布日志）")
    @PostMapping("/api/mdm/pubsub/publish")
    public R<Map<String, Object>> publish(@RequestBody DnMdmPublishLog req) {
        if (req.getGoldenRecordId() == null) throw new BusinessException("请指定黄金记录");
        DnMdmGoldenRecord g = goldenMapper.selectById(req.getGoldenRecordId());
        if (g == null) throw new ResourceNotFoundException("黄金记录");
        String type = req.getChangeType() == null ? "" : req.getChangeType().trim();
        if (!"create".equals(type) && !"update".equals(type) && !"delete".equals(type)) {
            throw new BusinessException("变更类型须为 create/update/delete");
        }
        // 扇出到匹配订阅(复用 MdmPublishService, 与黄金记录自动发布共用同一逻辑)
        int matched = mdmPublishService.fanOut(g, type);
        Map<String, Object> data = new HashMap<>();
        data.put("bizKey", g.getBizKey());
        data.put("changeType", type);
        data.put("matchedSubscriptions", matched);
        return R.ok(data);
    }

    @Operation(summary = "发布订阅统计（订阅数/发布次数/成功率）")
    @GetMapping("/api/mdm/pubsub/stats")
    public R<Map<String, Object>> stats() {
        // 用 DB count 精确统计, 避免把整张 publish_log(随发布持续增长) 全量载入内存
        long subCount = nz(subscriptionMapper.selectCount(new QueryWrapper<>()));
        long activeSubs = nz(subscriptionMapper.selectCount(new QueryWrapper<DnMdmSubscription>().eq("status", 1)));
        long publishCount = nz(publishLogMapper.selectCount(new QueryWrapper<>()));
        long success = nz(publishLogMapper.selectCount(new QueryWrapper<DnMdmPublishLog>().eq("status", "success")));
        Map<String, Object> data = new HashMap<>();
        data.put("subscriptionCount", subCount);
        data.put("activeSubscriptionCount", activeSubs);
        data.put("publishCount", publishCount);
        data.put("successCount", success);
        data.put("successRate", publishCount == 0 ? 100 : Math.round(success * 1000.0 / publishCount) / 10.0);
        return R.ok(data);
    }

    private long nz(Long c) {
        return c == null ? 0 : c;
    }

    // ------- 工具 -------
    /** 规整变更类型逗号串：仅保留 create/update/delete，去重去空，按固定顺序输出 */
    // normalizeChangeTypes 已抽到 MdmPublishService(R32 去重)
}
