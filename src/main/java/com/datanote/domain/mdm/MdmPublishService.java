package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.mdm.mapper.DnMdmPublishLogMapper;
import com.datanote.domain.mdm.mapper.DnMdmSubscriptionMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmPublishLog;
import com.datanote.domain.mdm.model.DnMdmSubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据发布订阅·扇出服务（R32）——黄金记录变更向匹配订阅写发布日志。
 * 抽出供「手动发布」(pubsub/publish) 与「黄金记录发布自动触发」(golden/publish) 共用，消除重复并打通自动闭环。
 */
@Service
@RequiredArgsConstructor
public class MdmPublishService {

    private final DnMdmSubscriptionMapper subscriptionMapper;
    private final DnMdmPublishLogMapper publishLogMapper;

    /** 对该黄金记录所属实体下、启用且订阅了该变更类型的订阅写发布日志，返回命中订阅数。 */
    public int fanOut(DnMdmGoldenRecord g, String changeType) {
        if (g == null || g.getEntityId() == null) return 0;
        String type = changeType == null ? "" : changeType.trim();
        if (!"create".equals(type) && !"update".equals(type) && !"delete".equals(type)) return 0;
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
        return matched;
    }

    /** 变更类型归一化（去空白/去重/固定 create,update,delete 顺序）。 */
    public String normalizeChangeTypes(String raw) {
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
