package com.datanote.common;

import com.datanote.common.util.SecretRedactor;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.platform.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志广播服务 — WebSocket 向前端推送实时日志/状态。
 * <p>私有化(P0): 鉴权开启且能解析任务归属时, 推到任务 owner 的私有队列 {@code /user/{owner}/queue/*}
 * (Spring 按会话 Principal 隔离, 他人收不到); 开放模式或无主任务回退全局 {@code /topic/*}。
 * 解析 owner 走 createdBy(脚本/同步任务/同步作业), 带缓存避免高频日志逐行查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private final AuthProperties authProperties;
    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final DnSyncJobMapper syncJobMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** owner 缓存(taskType:taskId → owner); owner 基本不变, 上限防膨胀。 */
    private final Map<String, String> ownerCache = new ConcurrentHashMap<>();
    private static final int OWNER_CACHE_MAX = 5000;

    /** 广播任务执行日志(私有: 仅任务 owner 可见)。 */
    public void broadcastTaskLog(Long taskId, String taskType, String level, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskType", taskType);
        payload.put("level", level);
        payload.put("message", message);
        payload.put("time", LocalDateTime.now().format(FMT));
        route(taskId, taskType, "task-log", payload);
    }

    /** 广播调度状态变更(私有)。 */
    public void broadcastStatusChange(Long taskId, String taskType, int status, String runDate) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskType", taskType);
        payload.put("status", status);
        payload.put("runDate", runDate);
        payload.put("time", LocalDateTime.now().format(FMT));
        route(taskId, taskType, "task-status", payload);
    }

    /** 广播同步任务状态变更(私有)。 */
    public void broadcastSyncStatus(Long jobId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", jobId);
        payload.put("taskType", "DbSync");
        payload.put("status", status);
        payload.put("time", LocalDateTime.now().format(FMT));
        route(jobId, "DbSync", "task-status", payload);
    }

    /** 广播系统通知(系统级, 面向全体登录用户, 保留全局)。 */
    public void broadcastNotification(String level, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("level", level);
        payload.put("message", message);
        payload.put("time", LocalDateTime.now().format(FMT));
        redact(payload);
        try {
            messagingTemplate.convertAndSend("/topic/notification", payload);
        } catch (Exception e) {
            log.warn("WebSocket 广播失败 dest=/topic/notification: {}", e.getMessage());
        }
    }

    /**
     * 按任务归属路由: 鉴权开启且解析到 owner → 私有队列; 否则全局 topic(开放模式/无主)。
     * 广播为辅助通知, 任何失败只记日志, 绝不冒泡中断调用方业务。
     */
    private void route(Long taskId, String taskType, String suffix, Map<String, Object> payload) {
        redact(payload);
        String owner = (authProperties != null && authProperties.isEnabled()) ? resolveOwner(taskId, taskType) : null;
        try {
            if (owner != null && !owner.isEmpty() && !"anonymous".equals(owner)) {
                messagingTemplate.convertAndSendToUser(owner, "/queue/" + suffix, payload);
            } else {
                messagingTemplate.convertAndSend("/topic/" + suffix, payload);
            }
        } catch (Exception e) {
            log.warn("WebSocket 广播失败 suffix={}: {}", suffix, e.getMessage());
        }
    }

    /** 解析任务归属(createdBy), 带缓存。解析不到返 null(回退全局)。 */
    private String resolveOwner(Long taskId, String taskType) {
        if (taskId == null || taskType == null) return null;
        String key = taskType + ":" + taskId;
        String cached = ownerCache.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String owner = null;
        try {
            if ("script".equals(taskType)) {
                DnScript s = scriptMapper.selectById(taskId);
                if (s != null) owner = s.getCreatedBy();
            } else if ("syncTask".equals(taskType)) {
                DnSyncTask t = syncTaskMapper.selectById(taskId);
                if (t != null) owner = t.getCreatedBy();
            } else if ("DbSync".equals(taskType)) {
                DnSyncJob j = syncJobMapper.selectById(taskId);
                if (j != null) owner = j.getCreatedBy();
            }
        } catch (Exception e) {
            return null;   // 查库异常: 回退全局, 不阻断
        }
        if (ownerCache.size() < OWNER_CACHE_MAX) ownerCache.put(key, owner == null ? "" : owner);
        return owner;
    }

    /** 脱敏 payload 中的 message 字符串(防密码/token 经日志泄露)。 */
    private void redact(Map<String, Object> payload) {
        Object message = payload.get("message");
        if (message instanceof String) {
            payload.put("message", SecretRedactor.redact((String) message));
        }
    }
}
