package com.datanote.platform.ai.agent.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent SSE 事件汇(特性C): 按 sessionId 维护订阅的 SseEmitter, 运行期推送 step/running/token/approval/question/done。
 * 纯附加: 无订阅者时所有 emit 为 no-op, 阻塞式 /chat 与轮询行为完全不变(降级铁律)。
 */
@Slf4j
@Component
public class AgentEventBus {

    private static final long TIMEOUT_MS = 5 * 60 * 1000L; // SSE 连接超时(配套保留轮询兜底)
    private final Map<String, List<SseEmitter>> subs = new ConcurrentHashMap<>();

    /** 订阅会话事件流(前端在发起 /chat 前打开)。 */
    public SseEmitter register(String sessionId) {
        SseEmitter em = new SseEmitter(TIMEOUT_MS);
        subs.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(em);
        em.onCompletion(() -> remove(sessionId, em));
        em.onTimeout(() -> { try { em.complete(); } catch (Exception ignore) {} remove(sessionId, em); });
        em.onError(e -> remove(sessionId, em));
        try { em.send(SseEmitter.event().name("open").data("{}")); } catch (Exception ignore) {}
        return em;
    }

    /** 是否有订阅者(决定是否走流式; 无人听则不流式, 保持阻塞路径不变)。 */
    public boolean hasSubscribers(String sessionId) {
        List<SseEmitter> l = sessionId == null ? null : subs.get(sessionId);
        return l != null && !l.isEmpty();
    }

    /** 推送事件(无订阅者直接返回)。data 由 Jackson 序列化为 JSON。 */
    public void emit(String sessionId, String event, Object data) {
        if (sessionId == null) return;
        List<SseEmitter> list = subs.get(sessionId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name(event).data(data == null ? "{}" : data));
            } catch (Exception e) {
                remove(sessionId, em); // 客户端已断开: 移除, 不影响其它
            }
        }
    }

    /** 运行结束: 关闭并清理该会话所有 emitter。 */
    public void complete(String sessionId) {
        List<SseEmitter> list = sessionId == null ? null : subs.remove(sessionId);
        if (list == null) return;
        for (SseEmitter em : list) { try { em.complete(); } catch (Exception ignore) {} }
    }

    private void remove(String sessionId, SseEmitter em) {
        List<SseEmitter> l = subs.get(sessionId);
        if (l != null) {
            l.remove(em);
            if (l.isEmpty()) subs.remove(sessionId);
        }
    }
}
