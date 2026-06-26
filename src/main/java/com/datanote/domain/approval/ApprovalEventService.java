package com.datanote.domain.approval;

import com.datanote.domain.approval.model.DnApproval;
import com.datanote.platform.notify.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批事件总线(Redis Streams)。复用已有 Redis(无新中间件): XADD 发布 + 消费组消费, 异步派发通知/下游 apply。
 * 降级铁律: Redis 故障一律不阻断审批主流程(DB 为准), 发布与消费均 fail-open。
 */
@Slf4j
@Service
public class ApprovalEventService {
    private static final String STREAM = "dn:approval:stream";
    private static final String GROUP = "dn-approval-cg";

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final DnApprovalMapper approvalMapper;
    private final NotificationService notificationService;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public ApprovalEventService(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                                DnApprovalMapper approvalMapper, NotificationService notificationService) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.approvalMapper = approvalMapper;
        this.notificationService = notificationService;
    }

    /** 发布审批事件(SUBMITTED/APPROVED/REJECTED), best-effort。 */
    public void publish(Long approvalId, String eventType) {
        if (approvalId == null) return;
        try {
            Map<String, String> m = new HashMap<>();
            m.put("id", String.valueOf(approvalId));
            m.put("type", eventType == null ? "" : eventType);
            m.put("ts", String.valueOf(System.currentTimeMillis()));
            redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(m));
        } catch (Exception e) {
            log.warn("审批事件发布失败(降级不阻断): id={} type={} err={}", approvalId, eventType, e.getMessage());
        }
    }

    @PostConstruct
    public void start() {
        try {
            ensureGroup();
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                    StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                            .pollTimeout(Duration.ofSeconds(2)).build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);
            container.receive(Consumer.from(GROUP, "dn-worker"),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()), this::onMessage);
            container.start();
            log.info("审批事件消费者已启动: stream={} group={}", STREAM, GROUP);
        } catch (Exception e) {
            log.warn("审批事件消费者启动失败(降级, 审批同步仍可用): {}", e.getMessage());
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e1) {
            // 流不存在 → 先建流再建组; 已存在(BUSYGROUP)忽略
            try {
                redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(Collections.singletonMap("type", "INIT")));
                redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
            } catch (Exception ignore) { /* 已存在 */ }
        }
    }

    private void onMessage(MapRecord<String, String, String> rec) {
        try {
            Map<String, String> v = rec.getValue();
            String idStr = v == null ? null : v.get("id");
            if (idStr != null && idStr.matches("\\d+")) {
                DnApproval a = approvalMapper.selectById(Long.valueOf(idStr));
                if (a != null) dispatch(a, v.getOrDefault("type", ""));
            }
        } catch (Exception e) {
            // 毒消息不卡死消费(内部审批, DB 为准, apply 幂等; 失败已 loud log, 可人工重触发)
            log.warn("审批事件处理失败(已跳过): {} err={}", rec.getId(), e.getMessage());
        } finally {
            try { redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId()); } catch (Exception ignore) {}
        }
    }

    private void dispatch(DnApproval a, String type) {
        // apply 已在 ApprovalService.review 同步完成; 此处仅做异步通知(解耦+可扩展审计/下游订阅)
        if ("APPROVED".equals(type)) {
            notify(a.getSubmitter(), "审批通过: " + nz(a.getTitle()), a.getId());
        } else if ("REJECTED".equals(type)) {
            String c = a.getReviewComment() == null || a.getReviewComment().isEmpty() ? "" : " — " + a.getReviewComment();
            notify(a.getSubmitter(), "审批驳回: " + nz(a.getTitle()) + c, a.getId());
        }
        // SUBMITTED: 仅入队(审批中心可见)
    }

    private void notify(String user, String title, Long id) {
        if (user == null || user.trim().isEmpty()) return;
        try {
            notificationService.notify(user.trim(), "APPROVAL", title, "approval", id, null);
        } catch (Exception ignore) {}
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @PreDestroy
    public void stop() {
        try { if (container != null) container.stop(); } catch (Exception ignore) {}
    }
}
