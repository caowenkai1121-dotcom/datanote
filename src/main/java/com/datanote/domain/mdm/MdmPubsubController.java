package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmEntityMapper;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.mapper.DnMdmPublishLogMapper;
import com.datanote.mapper.DnMdmSubscriptionMapper;
import com.datanote.model.DnMdmEntity;
import com.datanote.model.DnMdmGoldenRecord;
import com.datanote.model.DnMdmPublishLog;
import com.datanote.model.DnMdmSubscription;
import com.datanote.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据发布订阅(Pub/Sub) Controller —— 黄金记录变更向订阅系统发布。
 * 订阅方按实体+变更类型订阅，模拟发布时对匹配订阅写发布日志。
 */
@RestController
@RequestMapping("/api/mdm/pubsub")
@Tag(name = "主数据发布订阅", description = "黄金记录变更向订阅系统发布，订阅管理与发布日志")
@RequiredArgsConstructor
public class MdmPubsubController {

    private final DnMdmSubscriptionMapper subscriptionMapper;
    private final DnMdmPublishLogMapper publishLogMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;

    @Operation(summary = "订阅列表（按实体筛选，附实体名称）")
    @GetMapping("/subscriptions")
    public R<List<DnMdmSubscription>> subscriptions(@RequestParam(required = false) Long entityId) {
        QueryWrapper<DnMdmSubscription> qw = new QueryWrapper<>();
        if (entityId != null) qw.eq("entity_id", entityId);
        qw.orderByDesc("updated_at").last("LIMIT 500");
        List<DnMdmSubscription> rows = subscriptionMapper.selectList(qw);
        for (DnMdmSubscription s : rows) {
            if (s.getEntityId() != null) {
                DnMdmEntity e = entityMapper.selectById(s.getEntityId());
                if (e != null) s.setEntityName(e.getEntityName());
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "保存订阅（订阅方+实体+变更类型+推送地址）")
    @PostMapping("/subscription/save")
    public R<DnMdmSubscription> saveSubscription(@RequestBody DnMdmSubscription sub) {
        if (sub.getSubscriberSystem() == null || sub.getSubscriberSystem().trim().isEmpty()) {
            throw new BusinessException("订阅方系统不能为空");
        }
        if (sub.getEntityId() == null) throw new BusinessException("请先选择订阅的实体");
        if (entityMapper.selectById(sub.getEntityId()) == null) throw new ResourceNotFoundException("订阅的实体");
        String types = normalizeChangeTypes(sub.getChangeTypes());
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
    @DeleteMapping("/subscription/{id}")
    public R<String> deleteSubscription(@PathVariable Long id) {
        subscriptionMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "发布日志（按订阅筛选，附订阅方系统）")
    @GetMapping("/logs")
    public R<List<DnMdmPublishLog>> logs(@RequestParam(required = false) Long subscriptionId) {
        QueryWrapper<DnMdmPublishLog> qw = new QueryWrapper<>();
        if (subscriptionId != null) qw.eq("subscription_id", subscriptionId);
        qw.orderByDesc("published_at").last("LIMIT 500");
        List<DnMdmPublishLog> rows = publishLogMapper.selectList(qw);
        for (DnMdmPublishLog l : rows) {
            if (l.getSubscriptionId() != null) {
                DnMdmSubscription s = subscriptionMapper.selectById(l.getSubscriptionId());
                if (s != null) l.setSubscriberSystem(s.getSubscriberSystem());
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "模拟发布黄金记录变更（对匹配订阅写发布日志）")
    @PostMapping("/publish")
    public R<Map<String, Object>> publish(@RequestBody DnMdmPublishLog req) {
        if (req.getGoldenRecordId() == null) throw new BusinessException("请指定黄金记录");
        DnMdmGoldenRecord g = goldenMapper.selectById(req.getGoldenRecordId());
        if (g == null) throw new ResourceNotFoundException("黄金记录");
        String type = req.getChangeType() == null ? "" : req.getChangeType().trim();
        if (!"create".equals(type) && !"update".equals(type) && !"delete".equals(type)) {
            throw new BusinessException("变更类型须为 create/update/delete");
        }
        // 取该实体下启用且订阅了该变更类型的订阅
        QueryWrapper<DnMdmSubscription> qw = new QueryWrapper<>();
        qw.eq("entity_id", g.getEntityId()).eq("status", 1);
        List<DnMdmSubscription> subs = subscriptionMapper.selectList(qw);
        int matched = 0;
        for (DnMdmSubscription s : subs) {
            Set<String> subscribed = new HashSet<>(Arrays.asList(normalizeChangeTypes(s.getChangeTypes()).split(",")));
            if (!subscribed.contains(type)) continue;
            DnMdmPublishLog log = new DnMdmPublishLog();
            log.setSubscriptionId(s.getId());
            log.setGoldenRecordId(g.getId());
            log.setChangeType(type);
            log.setBizKey(g.getBizKey());
            log.setStatus("success");
            log.setMessage("已推送至 " + s.getSubscriberSystem() + " (" + s.getEndpoint() + ")");
            log.setPublishedAt(LocalDateTime.now());
            publishLogMapper.insert(log);
            matched++;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("bizKey", g.getBizKey());
        data.put("changeType", type);
        data.put("matchedSubscriptions", matched);
        return R.ok(data);
    }

    @Operation(summary = "发布订阅统计（订阅数/发布次数/成功率）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        List<DnMdmSubscription> subs = subscriptionMapper.selectList(new QueryWrapper<>());
        List<DnMdmPublishLog> logs = publishLogMapper.selectList(new QueryWrapper<>());
        long activeSubs = subs.stream().filter(s -> s.getStatus() != null && s.getStatus() == 1).count();
        long success = logs.stream().filter(l -> "success".equals(l.getStatus())).count();
        Map<String, Object> data = new HashMap<>();
        data.put("subscriptionCount", subs.size());
        data.put("activeSubscriptionCount", activeSubs);
        data.put("publishCount", logs.size());
        data.put("successCount", success);
        data.put("successRate", logs.isEmpty() ? 100 : Math.round(success * 1000.0 / logs.size()) / 10.0);
        return R.ok(data);
    }

    // ------- 工具 -------
    /** 规整变更类型逗号串：仅保留 create/update/delete，去重去空，按固定顺序输出 */
    private String normalizeChangeTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Set<String> set = new HashSet<>();
        for (String t : raw.split(",")) set.add(t.trim());
        List<String> ordered = new ArrayList<>();
        for (String t : new String[]{"create", "update", "delete"}) {
            if (set.contains(t)) ordered.add(t);
        }
        return String.join(",", ordered);
    }
}
