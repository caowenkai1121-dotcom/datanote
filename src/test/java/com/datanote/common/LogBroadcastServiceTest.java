package com.datanote.common;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LogBroadcastServiceTest {

    @Test
    void broadcastTaskLog_redactsSecretsInMessage() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        // 传 null(authProperties/mappers): 鉴权视为关闭 → 走全局 /topic, 验证脱敏行为不变
        LogBroadcastService service = new LogBroadcastService(messagingTemplate, null, null, null, null);

        service.broadcastTaskLog(1L, "script", "INFO",
                "password=plain-secret jdbc:mysql://root:db-secret@127.0.0.1/db token=abcdef123456");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/task-log"), payloadCaptor.capture());
        String message = String.valueOf(payloadCaptor.getValue().get("message"));

        assertFalse(message.contains("plain-secret"));
        assertFalse(message.contains("db-secret"));
        assertFalse(message.contains("abcdef123456"));
    }
}
