package com.datanote.domain.integration.engine;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.datanote.domain.integration.connector.StreamLoadTarget;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * DS-M4：Doris/StarRocks 原生 Stream Load 写入（JDK HttpURLConnection，零新依赖）。
 * <p>CSV 攒批导入，稳定 Label 实现导入级幂等（同 Label 重复提交被去重）。
 * FE 返回 307 时手动重定向到 BE 重发请求体（HttpURLConnection 不会自动带 body 重定向）。
 * 纯逻辑（label/csv/url/响应解析）可单测；HTTP 调用失败由调用方回退 JDBC。
 */
public final class StreamLoadWriter {
    private StreamLoadWriter() {}

    private static final char SEP = '\t';
    private static final String NULL = "\\N";

    /** 稳定 Label：仅 [A-Za-z0-9_-]，跨重试幂等。 */
    public static String buildLabel(Long jobId, Long execId, String table, long seq) {
        String t = table == null ? "t" : table.replaceAll("[^A-Za-z0-9_]", "_");
        return "dn_" + jobId + "_" + execId + "_" + t + "_" + seq;
    }

    /** 行集 -> CSV（\t 分隔，\N 表示 NULL，转义分隔符/换行/反斜杠）。 */
    public static String toCsv(List<Object[]> rows, int colCount) {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : rows) {
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(SEP);
                Object v = i < row.length ? row[i] : null;
                sb.append(v == null ? NULL : escape(String.valueOf(v)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s.indexOf('\\') < 0 && s.indexOf(SEP) < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == SEP) b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }

    public static String buildUrl(String host, int port, String db, String table) {
        return "http://" + host + ":" + port + "/api/" + db + "/" + table + "/_stream_load";
    }

    /** Stream Load 响应解析结果。 */
    public static final class Result {
        public final boolean success;
        public final long loadedRows;
        public final String status;
        public final String message;
        Result(boolean success, long loadedRows, String status, String message) {
            this.success = success; this.loadedRows = loadedRows; this.status = status; this.message = message;
        }
    }

    /**
     * 解析 Stream Load 返回 JSON。Success / Publish Timeout（数据已写，发布异步）/
     * Label Already Exists（同 Label 已导入，幂等成功）均视为成功；其余为失败。
     */
    public static Result parseResult(String json) {
        if (json == null || json.isEmpty()) return new Result(false, 0, "EMPTY", "空响应");
        JSONObject o;
        try { o = JSON.parseObject(json); } catch (Exception e) { return new Result(false, 0, "PARSE_ERR", json); }
        String status = o.getString("Status");
        String msg = o.getString("Message");
        long rows = o.getLongValue("NumberLoadedRows");
        boolean ok = "Success".equalsIgnoreCase(status)
                || "Publish Timeout".equalsIgnoreCase(status)
                || "Label Already Exists".equalsIgnoreCase(status);
        return new Result(ok, rows, status, msg);
    }

    /**
     * 执行一次 Stream Load。成功返回写入行数；失败抛异常（调用方回退 JDBC）。
     * @param connectTimeoutMs/readTimeoutMs 超时
     */
    public static long load(StreamLoadTarget t, String db, String table, List<String> columns,
                            List<Object[]> rows, String label) throws Exception {
        String body = toCsv(rows, columns.size());
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String url = buildUrl(t.host, t.httpPort, db, table);
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                ((t.user == null ? "" : t.user) + ":" + (t.password == null ? "" : t.password)).getBytes(StandardCharsets.UTF_8));
        String cols = String.join(",", columns);

        HttpURLConnection conn = open(url, auth, label, cols, data.length);
        int code = sendAndCode(conn, data);
        // FE 返回 307 -> 重定向到 BE，HttpURLConnection 不会自动带 body 重发，手动处理
        if (code == 307 || code == 301 || code == 302) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc == null || loc.isEmpty()) throw new RuntimeException("Stream Load 重定向无 Location");
            conn = open(loc, auth, label, cols, data.length);
            code = sendAndCode(conn, data);
        }
        String resp = readBody(conn, code);
        conn.disconnect();
        // 异常信息会进日志：响应体截断，避免泄漏服务端冗长/敏感错误内容
        if (code != 200) throw new RuntimeException("Stream Load HTTP " + code + ": " + truncate(resp, 200));
        Result r = parseResult(resp);
        if (!r.success) throw new RuntimeException("Stream Load 失败 status=" + r.status + " msg=" + truncate(r.message, 200));
        return r.loadedRows;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static HttpURLConnection open(String url, String auth, String label, String cols, int len) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Authorization", auth);
        conn.setRequestProperty("Expect", "100-continue");
        conn.setRequestProperty("label", label);
        conn.setRequestProperty("format", "csv");
        conn.setRequestProperty("column_separator", "\\t");
        conn.setRequestProperty("columns", cols);
        conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
        conn.setFixedLengthStreamingMode(len);
        return conn;
    }

    private static int sendAndCode(HttpURLConnection conn, byte[] data) throws Exception {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
            os.flush();
        }
        return conn.getResponseCode();
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try (java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (is == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取响应失败: " + e.getMessage();
        }
    }
}
