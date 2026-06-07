package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.mdm.mapper.DnMdmPublishLogMapper;
import com.datanote.domain.mdm.mapper.DnMdmSubscriptionMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmPublishLog;
import com.datanote.domain.mdm.model.DnMdmSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据发布订阅·扇出服务（R32）——黄金记录变更向匹配订阅写发布日志。
 * 抽出供「手动发布」(pubsub/publish) 与「黄金记录发布自动触发」(golden/publish) 共用，消除重复并打通自动闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmPublishService {

    private final DnMdmSubscriptionMapper subscriptionMapper;
    private final DnMdmPublishLogMapper publishLogMapper;

    /** 合法变更类型集合（顺序固定 create,update,delete，供归一化复用）。 */
    private static final String[] VALID_CHANGE_TYPES = {"create", "update", "delete"};

    /** 单次扇出读取订阅的上限，防止实体下订阅过多时一次性拉全表。 */
    private static final int MAX_SUBSCRIPTION_FANOUT = 1000;

    /**
     * 对该黄金记录所属实体下、启用且订阅了该变更类型的订阅写发布日志，返回命中订阅数。
     * 说明：本方法是内部扇出助手（调用方已各自做用户态校验/降级），对 null 记录或非法变更类型
     * 按既定语义返回 0（不抛异常），保证「黄金记录自动发布」链路的健壮降级。
     */
    @Transactional(rollbackFor = Exception.class)
    public int fanOut(DnMdmGoldenRecord g, String changeType) {
        if (g == null || g.getEntityId() == null) {
            log.warn("MDM 扇出跳过：黄金记录为空或缺少 entityId, changeType={}", changeType);
            return 0;
        }
        String type = changeType == null ? "" : changeType.trim();
        if (!isValidChangeType(type)) {
            log.warn("MDM 扇出跳过：非法变更类型 type={}, goldenRecordId={}", changeType, g.getId());
            return 0;
        }

        QueryWrapper<DnMdmSubscription> qw = new QueryWrapper<>();
        qw.eq("entity_id", g.getEntityId()).eq("status", 1)
                .last("LIMIT " + MAX_SUBSCRIPTION_FANOUT);
        List<DnMdmSubscription> subs = subscriptionMapper.selectList(qw);
        if (subs == null || subs.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int matched = 0;
        for (DnMdmSubscription s : subs) {
            if (s == null || s.getId() == null) {
                continue;
            }
            // 仅当该订阅明确订阅了当前变更类型才命中（归一化后按 ',' 切分，跳过空串避免误判）
            if (!subscribesType(s.getChangeTypes(), type)) {
                continue;
            }
            DnMdmPublishLog log = new DnMdmPublishLog();
            log.setSubscriptionId(s.getId());
            log.setGoldenRecordId(g.getId());
            log.setChangeType(type);
            log.setBizKey(g.getBizKey());
            log.setStatus("success");
            log.setMessage("已推送至 " + nullToEmpty(s.getSubscriberSystem())
                    + " (" + nullToEmpty(s.getEndpoint()) + ")");
            log.setPublishedAt(now);
            publishLogMapper.insert(log);
            matched++;
        }
        return matched;
    }

    /** 变更类型归一化（去空白/去重/固定 create,update,delete 顺序）。空或全非法返回 ""（调用方据此判空）。 */
    public String normalizeChangeTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Set<String> set = new HashSet<>();
        for (String t : raw.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        if (set.isEmpty()) return "";
        List<String> ordered = new ArrayList<>(VALID_CHANGE_TYPES.length);
        for (String t : VALID_CHANGE_TYPES) {
            if (set.contains(t)) ordered.add(t);
        }
        return String.join(",", ordered);
    }

    /** 判断给定变更类型逗号串是否订阅了 type（归一化后精确匹配，空串不命中）。 */
    private boolean subscribesType(String changeTypes, String type) {
        String normalized = normalizeChangeTypes(changeTypes);
        if (normalized.isEmpty()) return false;
        for (String t : normalized.split(",")) {
            if (t.equals(type)) return true;
        }
        return false;
    }

    /** 是否为合法变更类型（create/update/delete）。 */
    private boolean isValidChangeType(String type) {
        for (String t : VALID_CHANGE_TYPES) {
            if (t.equals(type)) return true;
        }
        return false;
    }

    /** null 安全：转空串，避免日志拼接出现 "null"。 */
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
