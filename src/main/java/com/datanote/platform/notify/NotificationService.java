package com.datanote.platform.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内通知（IV-1 第二步）。
 * 埋点契约: notify() 全程 fail-safe 不抛——通知是旁路, 永不影响业务主流程。
 * 四埋点: 任务指派 / 发布审批结果 / 指标预警建单 / 评论@我。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final DnNotificationMapper mapper;
    // 实时推送(WebSocket): notify 后推一帧给收件人, 前端订阅 /user/queue/notify 即时刷新铃铛(替代纯30s轮询)。field 注入避免改构造器, required=false 测试/无WS环境降级
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.messaging.simp.SimpMessagingTemplate messaging;

    /** fail-safe 写入: receiver 空或写库失败只记日志 */
    public void notify(String receiver, String type, String title, String refRoute, Long refId, String refTab) {
        try {
            if (receiver == null || receiver.trim().isEmpty()) return;
            DnNotification n = new DnNotification();
            n.setReceiver(receiver.trim());
            n.setType(type);
            n.setTitle(title == null ? "" : (title.length() > 300 ? title.substring(0, 300) : title));
            n.setRefRoute(refRoute);
            n.setRefId(refId);
            n.setRefTab(refTab);
            n.setCreatedAt(LocalDateTime.now());
            mapper.insert(n);
            // 实时推送给收件人(best-effort, 失败不影响): 前端 /user/queue/notify 收到即刷新铃铛
            try { if (messaging != null) messaging.convertAndSendToUser(receiver.trim(), "/queue/notify", "1"); } catch (Exception ignore) {}
        } catch (Exception e) {
            log.warn("通知写入失败(不影响主流程) receiver={} type={}: {}", receiver, type, e.getMessage());
        }
    }

    /** 未读数(轮询专用, 只 count 不拉行) */
    public long unreadCount(String receiver) {
        try {
            Long n = mapper.selectCount(new LambdaQueryWrapper<DnNotification>()
                    .eq(DnNotification::getReceiver, receiver).isNull(DnNotification::getReadAt));
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 最近 N 条(未读优先, 时间倒序) */
    public List<DnNotification> recent(String receiver, int limit) {
        List<DnNotification> rows = mapper.selectList(new LambdaQueryWrapper<DnNotification>()
                .eq(DnNotification::getReceiver, receiver)
                .orderByAsc(DnNotification::getReadAt)   // NULL(未读)排前
                .orderByDesc(DnNotification::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
        return rows == null ? new ArrayList<>() : rows;
    }

    /** 全部标记已读 */
    public void markAllRead(String receiver) {
        mapper.update(null, new LambdaUpdateWrapper<DnNotification>()
                .eq(DnNotification::getReceiver, receiver).isNull(DnNotification::getReadAt)
                .set(DnNotification::getReadAt, LocalDateTime.now()));
    }

    /** 单条已读 */
    public void markRead(String receiver, Long id) {
        mapper.update(null, new LambdaUpdateWrapper<DnNotification>()
                .eq(DnNotification::getId, id).eq(DnNotification::getReceiver, receiver)
                .set(DnNotification::getReadAt, LocalDateTime.now()));
    }
}
