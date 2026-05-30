package com.datanote.sync.service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@Slf4j @Service
public class AlertService {
    @Value("${datanote.alert.enabled:false}") private boolean enabled;
    @Value("${datanote.alert.webhook-urls:}") private String webhookUrls;
    @Value("${datanote.alert.dingtalk-webhook:}") private String dingtalk;
    @Value("${datanote.alert.throttle-min:30}") private int throttleMin;
    @Value("${datanote.alert.connect-timeout-ms:3000}") private int connectTimeout;
    @Value("${datanote.alert.read-timeout-ms:5000}") private int readTimeout;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"datanote-alert"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String,Long> lastSent = new ConcurrentHashMap<>();
    public void alert(Long jobId, String jobName, String type, String message) {
        if (!enabled) return;
        String key = jobId + ":" + type;
        long now = System.currentTimeMillis();
        if (throttled(lastSent.get(key), now, throttleMin)) return;
        lastSent.put(key, now);
        String text = "[DataNote同步告警] 任务#" + jobId + " " + (jobName==null?"":jobName) + " | " + type + " | " + message;
        pool.execute(() -> send(text));
    }
    public static boolean throttled(Long last, long now, int throttleMin) {
        return last != null && (now - last) < throttleMin * 60_000L;
    }
    private void send(String text) {
        if (dingtalk != null && !dingtalk.isEmpty()) postJson(dingtalk, "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escape(text) + "\"}}");
        if (webhookUrls != null && !webhookUrls.isEmpty()) for (String u : webhookUrls.split(",")) if (!u.trim().isEmpty()) postJson(u.trim(), "{\"text\":\"" + escape(text) + "\"}");
    }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private void postJson(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(connectTimeout); conn.setReadTimeout(readTimeout); conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            if (code >= 300) log.warn("告警发送返回 {} url={}", code, url);
            conn.disconnect();
        } catch (Exception e) { log.warn("告警发送失败 url={}: {}", url, e.getMessage()); }
    }
}
