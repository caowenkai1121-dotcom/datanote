package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.mdm.mapper.DnMdmPublishLogMapper;
import com.datanote.domain.mdm.mapper.DnMdmSubscriptionMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmPublishLog;
import com.datanote.domain.mdm.model.DnMdmSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
    private final ObjectMapper objectMapper;

    /** 推送超时(ms): 连接/读各自上限, 防订阅端不通拖死发布。 */
    private static final int PUSH_TIMEOUT_MS = 3000;

    /** 合法变更类型集合（顺序固定 create,update,delete，供归一化复用）。 */
    private static final String[] VALID_CHANGE_TYPES = {"create", "update", "delete"};

    /** 单次扇出读取订阅的上限，防止实体下订阅过多时一次性拉全表。 */
    private static final int MAX_SUBSCRIPTION_FANOUT = 1000;

    /**
     * 对该黄金记录所属实体下、启用且订阅了该变更类型的订阅写发布日志，返回命中订阅数。
     * 说明：本方法是内部扇出助手（调用方已各自做用户态校验/降级），对 null 记录或非法变更类型
     * 按既定语义返回 0（不抛异常），保证「黄金记录自动发布」链路的健壮降级。
     */
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
            DnMdmPublishLog plog = new DnMdmPublishLog();
            plog.setSubscriptionId(s.getId());
            plog.setGoldenRecordId(g.getId());
            plog.setChangeType(type);
            plog.setBizKey(g.getBizKey());
            plog.setPublishedAt(now);
            // 真实推送: 配了 endpoint 就 HTTP POST 黄金记录, 按响应码记真实成功/失败; 没配则登记 skipped(不再谎报 success)
            String endpoint = s.getEndpoint() == null ? "" : s.getEndpoint().trim();
            if (endpoint.isEmpty()) {
                plog.setStatus("skipped");
                plog.setMessage("订阅未配置 endpoint, 仅登记未推送");
            } else {
                String[] res = pushHttp(endpoint, buildPayload(g, type, now));
                plog.setStatus(res[0]);
                plog.setMessage(cap("→ " + nullToEmpty(s.getSubscriberSystem()) + " " + endpoint + " | " + res[1], 480));
            }
            publishLogMapper.insert(plog);
            matched++;
        }
        return matched;
    }

    /** 真实 HTTP POST 黄金记录到订阅端点(JDK 内置 HttpURLConnection, 零依赖)。返回 [status, message]。 */
    private String[] pushHttp(String endpoint, String payload) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(endpoint);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(PUSH_TIMEOUT_MS);
            conn.setReadTimeout(PUSH_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return new String[]{"success", "HTTP " + code + " 推送成功"};
            return new String[]{"failed", "HTTP " + code + " 推送失败"};
        } catch (Exception e) {
            return new String[]{"failed", "推送异常: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 构建推送载荷(黄金记录全量 + 变更类型), data 由 dataJson 解析为对象, 失败则原样字符串。 */
    private String buildPayload(DnMdmGoldenRecord g, String type, LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goldenRecordId", g.getId());
        m.put("entityId", g.getEntityId());
        m.put("bizKey", g.getBizKey());
        m.put("changeType", type);
        m.put("status", g.getStatus());
        m.put("version", g.getVersion());
        String json = g.getDataJson();
        Object data = json;
        try {
            if (json != null && !json.trim().isEmpty()) data = objectMapper.readValue(json, Map.class);
        } catch (Exception ignore) {}
        m.put("data", data);
        m.put("publishedAt", now.toString());
        try { return objectMapper.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
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
