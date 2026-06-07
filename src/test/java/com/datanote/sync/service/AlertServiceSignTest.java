package com.datanote.domain.integration.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M5：钉钉加签纯逻辑 + 节流。 */
public class AlertServiceSignTest {

    @Test
    void signDeterministicAndUrlSafe() throws Exception {
        String s1 = AlertService.computeSign("SEC123", 1700000000000L);
        String s2 = AlertService.computeSign("SEC123", 1700000000000L);
        assertEquals(s1, s2, "同 secret+timestamp 应确定性");
        assertFalse(s1.isEmpty());
        // URL 编码后不含原始 + / =（base64 的特殊字符应被转义）
        assertFalse(s1.contains(" "));
        assertNotEquals(AlertService.computeSign("SEC123", 1700000000001L), s1, "时间戳不同签名不同");
    }

    @Test
    void signedUrlAppendsWhenSecretPresent() throws Exception {
        String base = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        String signed = AlertService.signedUrl(base, "SEC", 1700000000000L);
        assertTrue(signed.startsWith(base + "&timestamp=1700000000000&sign="));
        // 无 secret 返回原 url
        assertEquals(base, AlertService.signedUrl(base, "", 1L));
        assertEquals(base, AlertService.signedUrl(base, null, 1L));
        // base 无 ? 时用 ? 起始
        assertTrue(AlertService.signedUrl("http://h/w", "S", 5L).startsWith("http://h/w?timestamp=5&sign="));
    }

    @Test
    void throttle() {
        long now = 1_000_000_000L;
        assertFalse(AlertService.throttled(null, now, 30));
        assertTrue(AlertService.throttled(now - 60_000L, now, 30), "1分钟内,30分钟节流应拦截");
        assertFalse(AlertService.throttled(now - 31 * 60_000L, now, 30), "超31分钟应放行");
    }
}
