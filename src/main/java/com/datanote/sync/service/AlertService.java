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
    @Value("${datanote.alert.dingtalk-secret:}") private String dingtalkSecret;
    @Value("${datanote.alert.at-mobiles:}") private String atMobiles;
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
        if (dingtalk != null && !dingtalk.isEmpty()) {
            String content = text;
            String at = "";
            // @手机号：内容前缀 @号码 + at.atMobiles，钉钉才会真正 @ 到人
            if (atMobiles != null && !atMobiles.trim().isEmpty()) {
                StringBuilder ats = new StringBuilder(), prefix = new StringBuilder();
                for (String mb : atMobiles.split(",")) {
                    String m = mb.trim();
                    if (m.isEmpty()) continue;
                    if (ats.length() > 0) ats.append(",");
                    ats.append("\"").append(escape(m)).append("\"");
                    prefix.append("@").append(m).append(" ");
                }
                content = prefix + text;
                at = ",\"at\":{\"atMobiles\":[" + ats + "],\"isAtAll\":false}";
            }
            String url = dingtalk;
            try {
                if (dingtalkSecret != null && !dingtalkSecret.isEmpty()) url = signedUrl(dingtalk, dingtalkSecret, System.currentTimeMillis());
            } catch (Exception e) { log.warn("钉钉加签失败,改用未签名 url: {}", e.getMessage()); }
            postJson(url, "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escape(content) + "\"}" + at + "}");
        }
        if (webhookUrls != null && !webhookUrls.isEmpty()) for (String u : webhookUrls.split(",")) if (!u.trim().isEmpty()) postJson(u.trim(), "{\"text\":\"" + escape(text) + "\"}");
    }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }

    /** 钉钉加签：sign = urlEncode(base64(HmacSHA256(timestamp+"\n"+secret, secret)))。可单测。 */
    public static String computeSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] data = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(data), "UTF-8");
    }

    /** 拼接加签后的钉钉 webhook url；secret 为空返回原 url。 */
    public static String signedUrl(String base, String secret, long timestamp) throws Exception {
        if (secret == null || secret.isEmpty()) return base;
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "timestamp=" + timestamp + "&sign=" + computeSign(secret, timestamp);
    }
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
