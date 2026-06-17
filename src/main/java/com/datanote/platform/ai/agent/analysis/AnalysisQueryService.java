package com.datanote.platform.ai.agent.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.governance.AssetDetailService;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.governance.SqlMaskRewriter;
import com.datanote.domain.integration.connector.ConnectionManager;
import com.datanote.domain.integration.util.SqlExecutor;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.config.HiveConfig;
import com.datanote.platform.iam.DataAclService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读分析查询安全栈 — 供 run_analysis 工具调用。
 *
 * 安全顺序（强制，缺任一层即不执行）：
 *  1) SQL 语法验证：非空、去注释、单语句、SELECT/WITH 开头、拒 INTO OUTFILE/DUMPFILE
 *  2) 数据级 ACL：按 SQL 中引用的每张表调用 DataAclService.canAccessAs
 *  3) 脱敏改写(fail-closed)：SqlMaskRewriter.rewrite；SELECT* / 子查询含策略时抛 MaskRewriteException
 *  4) 执行：数据源路由(源库MySQL/数仓Doris) + setMaxRows(2000) + setQueryTimeout(30)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {

    private static final int MAX_ROWS = 2000;
    private static final int QUERY_TIMEOUT_SEC = 30;
    private static final int CELL_MAX_LEN = 200;

    private final AssetDetailService assetDetailService;
    private final ConnectionManager connectionManager;
    private final HiveConfig hiveConfig;
    private final MaskingService maskingService;
    private final DataAclService dataAclService;
    private final TaskDependencyService taskDependencyService;
    private final DnTableMetaMapper tableMetaMapper;

    /**
     * 执行只读分析查询，返回 {columns, rows, returned, limit, source}。
     *
     * @param sql 用户提供的 SQL（必须为单条 SELECT/WITH）
     * @param db  默认库（null 时使用数仓 Doris）
     * @param ctx AgentContext（提供调用者身份，不从参数取）
     * @throws IllegalArgumentException SQL 语法/结构不合法
     * @throws SecurityException        无数据资源访问权限
     * @throws SqlMaskRewriter.MaskRewriteException 命中脱敏策略无法改写（调用方 fail-closed）
     * @throws java.sql.SQLException    执行失败
     */
    public Map<String, Object> runSelect(String sql, String db, AgentContext ctx)
            throws SqlMaskRewriter.MaskRewriteException, java.sql.SQLException {

        // ── 第一层：SQL 语法验证 ────────────────────────────────────────────
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql 不能为空");
        }

        // 去注释：行注释 -- ... 和块注释 /* ... */
        String clean = sql.replaceAll("--[^\r\n]*", " ")
                          .replaceAll("/\\*[\\s\\S]*?\\*/", " ")
                          .trim();

        if (clean.isEmpty()) {
            throw new IllegalArgumentException("去除注释后 sql 为空");
        }

        // 单语句检测
        List<String> stmts = SqlExecutor.splitStatements(clean);
        if (stmts.size() != 1) {
            throw new IllegalArgumentException(
                    "仅允许单条 SELECT/WITH 语句，当前检测到 " + stmts.size() + " 条语句");
        }

        // 只允许 SELECT 或 WITH（CTE）
        String upper = clean.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException(
                    "仅允许 SELECT/WITH 查询，拒绝执行: " + clean.substring(0, Math.min(80, clean.length())));
        }

        // 拒绝 INTO OUTFILE / INTO DUMPFILE
        if (upper.matches("(?s).*\\bINTO\\s+(OUTFILE|DUMPFILE)\\b.*")) {
            throw new IllegalArgumentException("禁止 INTO OUTFILE/DUMPFILE 导出语句");
        }

        // ── 第二层：数据级 ACL ──────────────────────────────────────────────
        Set<String> tables = taskDependencyService.parseSQLTables(sql);
        if (!tables.isEmpty()) {
            String caller = ctx.getUserName();
            List<String> roles = ctx.getRoles();
            Set<String> perms = ctx.getPerms();
            for (String table : tables) {
                String rid = (db != null ? db : "") + "." + table;
                if (!dataAclService.canAccessAs(caller, roles, perms, "TABLE", rid)) {
                    throw new SecurityException("无数据资源 " + rid + " 访问权限");
                }
            }
        }

        // ── 第三层：脱敏改写(fail-closed) ──────────────────────────────────
        List<SqlMaskRewriter.ColumnMask> masks = maskingService.resolveColumnMasks();
        List<SqlMaskRewriter.RowFilter> filters = maskingService.resolveRowFilters(ctx.getUserName());
        // rewrite 在策略存在但 SELECT*/子查询/解析失败时抛 MaskRewriteException，直接向上传播
        String finalSql = SqlMaskRewriter.rewrite(sql, db, masks, filters);

        // ── 第四层：执行 ────────────────────────────────────────────────────
        SourceRoute route = resolveRoute(db);
        Connection conn = null;
        try {
            conn = route.useSource ? connectionManager.getConnection(route.datasourceId, db)
                                   : hiveConfig.getConnection();
            List<String> columns = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(QUERY_TIMEOUT_SEC);
                st.setMaxRows(MAX_ROWS);
                try (ResultSet rs = st.executeQuery(finalSql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cc = md.getColumnCount();
                    for (int i = 1; i <= cc; i++) {
                        columns.add(md.getColumnLabel(i));
                    }
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= cc; i++) {
                            Object v = rs.getObject(i);
                            String s = v == null ? null : String.valueOf(v);
                            if (s != null && s.length() > CELL_MAX_LEN) {
                                s = s.substring(0, CELL_MAX_LEN) + "…";
                            }
                            row.add(s);
                        }
                        rows.add(row);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("returned", rows.size());
            result.put("limit", MAX_ROWS);
            result.put("source", route.useSource ? "源库" : "数仓");
            return result;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    /** 数据源路由结果 */
    private static final class SourceRoute {
        final boolean useSource;
        final Long datasourceId;
        SourceRoute(boolean useSource, Long datasourceId) {
            this.useSource = useSource;
            this.datasourceId = datasourceId;
        }
    }

    /**
     * 镜像 AssetDetailService.read 的路由逻辑：
     * db 非空 且 元数据中存在 datasourceId 且 dbType 含 MYSQL → 走源库；否则走数仓。
     */
    private SourceRoute resolveRoute(String db) {
        if (db == null || db.trim().isEmpty()) return new SourceRoute(false, null);
        try {
            DnTableMeta meta = tableMetaMapper.selectOne(new QueryWrapper<DnTableMeta>()
                    .eq("database_name", db).isNotNull("datasource_id").last("LIMIT 1"));
            if (meta != null && meta.getDatasourceId() != null
                    && meta.getDbType() != null && meta.getDbType().toUpperCase().contains("MYSQL")) {
                return new SourceRoute(true, meta.getDatasourceId());
            }
        } catch (Exception e) {
            log.warn("路由判断失败, 降级数仓: db={}, err={}", db, e.getMessage());
        }
        return new SourceRoute(false, null);
    }
}
