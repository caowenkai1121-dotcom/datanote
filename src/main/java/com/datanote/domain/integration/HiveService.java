package com.datanote.domain.integration;

import com.datanote.common.Constants;
import com.datanote.config.HiveConfig;
import com.datanote.model.ColumnInfo;
import com.datanote.util.DorisSqlUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HiveService {

    private static final Logger log = LoggerFactory.getLogger(HiveService.class);

    private final HiveConfig hiveConfig;
    private final SimpMessagingTemplate messagingTemplate;

    public String generateDDL(String sourceDb, String sourceTable, List<ColumnInfo> columns, String syncMode) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("No source columns found for " + sourceDb + "." + sourceTable);
        }
        String odsTable = getOdsTableName(sourceDb, sourceTable, syncMode);
        List<String> dorisColumns = DorisSqlUtil.toDorisColumnNames(columns);

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE IF NOT EXISTS ods.").append(odsTable).append(" (\n");
        ddl.append("  `dt` VARCHAR(10) COMMENT 'sync date'");

        for (int i = 0; i < dorisColumns.size(); i++) {
            ColumnInfo column = columns.get(i);
            String columnName = dorisColumns.get(i);
            ddl.append(",\n  ").append(DorisSqlUtil.quoteIdentifier(columnName)).append(" STRING");
            if (column.getComment() != null && !column.getComment().isEmpty()) {
                ddl.append(" COMMENT '").append(DorisSqlUtil.escapeSqlLiteral(column.getComment())).append("'");
            }
        }

        ddl.append("\n)\n");
        ddl.append("ENGINE=OLAP\n");
        ddl.append("DUPLICATE KEY(`dt`)\n");
        ddl.append("COMMENT 'ODS ").append(DorisSqlUtil.escapeSqlLiteral(sourceDb))
                .append(".").append(DorisSqlUtil.escapeSqlLiteral(sourceTable)).append(" sync'\n");
        ddl.append("DISTRIBUTED BY RANDOM BUCKETS 10\n");
        ddl.append("PROPERTIES (\n");
        ddl.append("  \"replication_num\" = \"1\"\n");
        ddl.append(")");

        return ddl.toString();
    }

    public void executeDDL(String ddl) throws Exception {
        log.info("Execute Doris DDL:\n{}", ddl);
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS ods");
            stmt.execute(ddl);
            log.info("Doris DDL executed successfully");
        }
    }

    public Map<String, Object> executeSQL(String sql) throws Exception {
        Map<String, Object> result = new HashMap<>();
        long start = System.currentTimeMillis();

        try (Connection conn = hiveConfig.getRawConnection();
             Statement stmt = conn.createStatement()) {
            String trimmed = trimSql(sql);
            String firstStatement = firstExecutableLine(trimmed);

            if (firstStatement == null) {
                List<String> logs = Collections.singletonList("[skip] SQL contains only comments or blank lines");
                result.put("type", "execute");
                result.put("message", "No executable SQL");
                result.put("duration", System.currentTimeMillis() - start);
                result.put("success", true);
                putLogs(result, logs);
                return result;
            }

            List<String> queryLogs = new ArrayList<>();
            queryLogs.add("[execute SQL] " + previewSql(trimmed));

            if (isQuery(firstStatement)) {
                try (ResultSet rs = stmt.executeQuery(trimmed)) {
                    readResultSet(rs, result);
                }
                queryLogs.add("[query done] rows=" + result.get("rowCount")
                        + ", columns=" + ((List<?>) result.get("columns")).size()
                        + ", duration=" + (System.currentTimeMillis() - start) + "ms");
            } else {
                boolean hasResultSet = stmt.execute(trimmed);
                result.put("type", hasResultSet ? "query" : "execute");
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        readResultSet(rs, result);
                    }
                } else {
                    result.put("message", "Execute success");
                    result.put("updateCount", stmt.getUpdateCount());
                }
                queryLogs.add("[execute done] duration=" + (System.currentTimeMillis() - start) + "ms");
            }

            result.put("duration", System.currentTimeMillis() - start);
            result.put("success", true);
            putLogs(result, queryLogs);
            return result;
        } catch (Exception e) {
            List<String> errLogs = Collections.singletonList("[error] " + e.getMessage());
            result.put("success", false);
            result.put("duration", System.currentTimeMillis() - start);
            result.put("error", e.getMessage());
            putLogs(result, errLogs);
            throw e;
        }
    }

    private void readResultSet(ResultSet rs, Map<String, Object> result) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<List<String>> rows = new ArrayList<>();
        int rowLimit = Constants.MAX_QUERY_ROWS;
        int count = 0;
        while (rs.next() && count < rowLimit) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                String val = rs.getString(i);
                row.add(val == null ? "NULL" : val);
            }
            rows.add(row);
            count++;
        }

        result.put("type", "query");
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("rowCount", count);
        result.put("truncated", count >= rowLimit);
    }

    public String getOdsTableName(String sourceDb, String sourceTable, String syncMode) {
        validateName(sourceDb, "database name");
        validateName(sourceTable, "table name");
        String suffix = "df";
        if (syncMode != null && (syncMode.equals("di") || syncMode.equals("incr"))) {
            suffix = "di";
        }
        return "ods_" + sourceDb + "_" + sourceTable.toLowerCase() + "_" + suffix;
    }

    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[a-zA-Z0-9_]+");

    private void validateName(String name, String label) {
        if (name == null || name.isEmpty() || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": " + name);
        }
    }

    public interface LogCallback {
        void onLog(String level, String message);
        void onResult(Map<String, Object> result);
        void onError(String error);
    }

    public void executeSQLWithStream(String sql, LogCallback callback) {
        try (Connection conn = hiveConfig.getRawConnection();
             Statement stmt = conn.createStatement()) {
            List<String> validStmts = splitValidSql(sql);
            if (validStmts.isEmpty()) {
                callback.onError("No executable SQL");
                return;
            }

            Map<String, Object> lastResult = new HashMap<>();
            int total = validStmts.size();
            for (int i = 0; i < total; i++) {
                String stmtSql = trimSql(validStmts.get(i));
                String firstLine = firstExecutableLine(stmtSql);
                boolean query = firstLine != null && isQuery(firstLine);
                long start = System.currentTimeMillis();
                callback.onLog("INFO", "[" + (i + 1) + "/" + total + "] Execute: " + previewSql(stmtSql));

                if (query) {
                    try (ResultSet rs = stmt.executeQuery(stmtSql)) {
                        readResultSet(rs, lastResult);
                    }
                    callback.onLog("OK", "[" + (i + 1) + "/" + total + "] Query done, rows="
                            + lastResult.get("rowCount") + ", duration=" + (System.currentTimeMillis() - start) + "ms");
                } else {
                    boolean hasResultSet = stmt.execute(stmtSql);
                    if (hasResultSet) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            readResultSet(rs, lastResult);
                        }
                    } else {
                        lastResult.put("type", "execute");
                        lastResult.put("success", true);
                        lastResult.put("duration", System.currentTimeMillis() - start);
                        lastResult.put("message", "Execute success");
                    }
                    callback.onLog("OK", "[" + (i + 1) + "/" + total + "] Execute done, duration="
                            + (System.currentTimeMillis() - start) + "ms");
                }
            }

            callback.onLog("OK", "All " + total + " statements completed");
            callback.onResult(lastResult);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private List<String> splitValidSql(String sql) {
        List<String> validStmts = new ArrayList<>();
        if (sql == null) {
            return validStmts;
        }
        String[] parts = sql.split(";");
        for (String part : parts) {
            String clean = part.replaceAll("--[^\\n]*", "").trim();
            if (!clean.isEmpty()) {
                validStmts.add(part.trim());
            }
        }
        return validStmts;
    }

    private String trimSql(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String firstExecutableLine(String sql) {
        if (sql == null) {
            return null;
        }
        String[] lines = sql.split("\\n");
        for (String line : lines) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("--")) {
                return l;
            }
        }
        return null;
    }

    private boolean isQuery(String firstStatement) {
        String upper = firstStatement.toUpperCase();
        return upper.startsWith("SELECT")
                || upper.startsWith("SHOW")
                || upper.startsWith("DESCRIBE")
                || upper.startsWith("DESC")
                || upper.startsWith("EXPLAIN")
                || upper.startsWith("WITH");
    }

    private String previewSql(String sql) {
        String oneLine = sql.replace('\n', ' ').trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "..." : oneLine;
    }

    private void putLogs(Map<String, Object> result, List<String> logs) {
        result.put("dorisLogs", logs);
        result.put("hiveLogs", logs);
    }

    private void pushLog(String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("level", "DORIS");
            payload.put("message", message);
            payload.put("time", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            messagingTemplate.convertAndSend("/topic/sql-log", payload);
        } catch (Exception ignored) {
        }
    }
}
