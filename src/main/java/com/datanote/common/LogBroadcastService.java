package com.datanote.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志广播服务 — 通过 WebSocket 向前端推送实时日志
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 广播任务执行日志
     *
     * @param taskId   任务ID
     * @param taskType 任务类型 (script/syncTask)
     * @param level    日志级别 (INFO/WARN/ERROR)
     * @param message  日志内容
     */
    public void broadcastTaskLog(Long taskId, String taskType, String level, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskType", taskType);
        payload.put("level", level);
        payload.put("message", message);
        payload.put("time", LocalDateTime.now().format(FMT));
        send("/topic/task-log", payload);
    }

    /**
     * 广播调度状态变更
     *
     * @param taskId   任务ID
     * @param taskType 任务类型
     * @param status   新状态
     * @param runDate  调度日期
     */
    public void broadcastStatusChange(Long taskId, String taskType, int status, String runDate) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskType", taskType);
        payload.put("status", status);
        payload.put("runDate", runDate);
        payload.put("time", LocalDateTime.now().format(FMT));
        send("/topic/task-status", payload);
    }

    /**
     * 广播同步任务状态变更（字符串状态，区别于 broadcastStatusChange 的 int 调度状态）。
     * 推到 /topic/task-status，前端据 taskId+taskType 实时刷新任务状态。
     *
     * @param jobId  同步任务ID
     * @param status 新状态（RUNNING/SUCCESS/FAILED 等）
     */
    public void broadcastSyncStatus(Long jobId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", jobId);
        payload.put("taskType", "DbSync");
        payload.put("status", status);
        payload.put("time", LocalDateTime.now().format(FMT));
        send("/topic/task-status", payload);
    }

    /**
     * 广播系统通知
     *
     * @param level   通知级别
     * @param message 通知内容
     */
    public void broadcastNotification(String level, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("level", level);
        payload.put("message", message);
        payload.put("time", LocalDateTime.now().format(FMT));
        send("/topic/notification", payload);
    }

    /** 统一发送：广播为辅助通知，失败(broker 不可用/序列化异常)只记日志，绝不冒泡中断调用方业务流程。 */
    private void send(String destination, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("WebSocket 广播失败 dest={}: {}", destination, e.getMessage());
        }
    }
}
