package com.datanote.platform.ai.agent.analysis;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.JdbcConstants;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.governance.AssetDetailService;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.governance.SqlMaskRewriter;
import com.datanote.domain.integration.connector.ConnectionManager;
import com.datanote.domain.integration.util.SqlExecutor;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读分析查询安全栈 — 供 run_analysis 工具调用。
 *
 * 安全顺序（强制，缺任一层即不执行）：
 *  1) SQL 语法验证：非空、拒可执行注释/*!、去注释、单语句、SELECT/WITH 开头、拒 INTO OUTFILE/DUMPFILE
 *  2) 数据级 ACL：Druid 解析提取每张引用表的真实(schema,table)逐表 DataAclService.canAccessAs；解析失败 fail-closed
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

        // 拒绝 MySQL 可执行注释 /*! ... */（去注释正则会吞掉它, 故先用原始 sql 检测）
        if (sql.contains("/*!")) {
            throw new IllegalArgumentException("不支持可执行注释 /*! */");
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

        // ── 第二层：数据级 ACL（Druid 解析真实库名，覆盖跨库/逗号连接/子查询每张表）─────
        // 用 clean(已去注释)解析，提取所有引用表的 (schema, table)。解析失败 fail-closed 拒绝。
        List<TableRef> refs = extractReferencedTables(clean);
        if (!refs.isEmpty()) {
            String caller = ctx.getUserName();
            List<String> roles = ctx.getRoles();
            Set<String> perms = ctx.getPerms();
            for (TableRef ref : refs) {
                // schema 非空用真实库名, 否则回退到 db 参数(默认数仓库)
                String schema = (ref.db != null && !ref.db.isEmpty()) ? ref.db : (db != null ? db : "");
                String rid = schema + "." + ref.table;
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

        // 无 LIMIT 的查询自动补 LIMIT, 让 Doris 提前短路减少全表扫描(聚合查询补了也无害; 已有 LIMIT 则不动)
        String execSql = finalSql.trim();
        while (execSql.endsWith(";")) execSql = execSql.substring(0, execSql.length() - 1).trim();
        if (!execSql.toLowerCase().matches("(?s).*\\blimit\\s+\\d.*")) execSql = execSql + " LIMIT " + MAX_ROWS; // 须 limit+数字, 排除字符串/列名里的 limit

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
                try (ResultSet rs = st.executeQuery(execSql)) {
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

    /** 引用表：库(可空) + 表。 */
    private static final class TableRef {
        final String db;
        final String table;
        TableRef(String db, String table) { this.db = db; this.table = table; }
    }

    /**
     * 用 Druid 解析 SQL，提取所有引用表的真实 (schema, table)。
     * 复用 SqlLineageParser 同款 SchemaStatVisitor 用法(MySQL 方言, Doris 兼容)，
     * 覆盖跨库 db.table、逗号连接 FROM a,b、JOIN、子查询的每张物理表；剔除 CTE(WITH)别名。
     *
     * fail-closed：解析抛异常(无法确定引用表)→ 抛 SecurityException 拒绝执行。
     * 解析成功但零物理表(如 SELECT 1 字面量)→ 返回空列表(无数据访问，放行)。
     */
    private List<TableRef> extractReferencedTables(String clean) {
        List<TableRef> out = new ArrayList<>();
        List<SQLStatement> stmts;
        try {
            stmts = SQLUtils.parseStatements(clean, JdbcConstants.MYSQL);
        } catch (Exception e) {
            throw new SecurityException("无法解析查询引用的表, 拒绝执行: " + e.getMessage());
        }
        if (stmts == null || stmts.isEmpty()) {
            throw new SecurityException("无法解析查询引用的表, 拒绝执行");
        }
        try {
            // 收集所有 CTE 名(WITH 子查询别名, 非物理表)
            Set<String> cteNames = new HashSet<>();
            SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(JdbcConstants.MYSQL);
            for (SQLStatement stmt : stmts) {
                stmt.accept(visitor);
                if (stmt instanceof SQLSelectStatement) {
                    SQLSelect select = ((SQLSelectStatement) stmt).getSelect();
                    if (select != null) collectCteNames(select.getWithSubQuery(), cteNames);
                }
            }
            Set<String> seen = new HashSet<>();
            for (TableStat.Name name : visitor.getTables().keySet()) {
                String full = name.getName(); // 形如 db.table 或 table
                if (full == null) continue;
                String lower = full.toLowerCase();
                if (cteNames.contains(lower)) continue; // CTE 名剔除
                TableRef ref = parseQualified(full);
                if (ref == null) continue;
                if (seen.add((ref.db == null ? "" : ref.db.toLowerCase()) + "." + ref.table.toLowerCase())) {
                    out.add(ref);
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            // 提取过程异常同样 fail-closed
            throw new SecurityException("解析查询引用表失败, 拒绝执行: " + e.getMessage());
        }
        return out;
    }

    private static void collectCteNames(SQLWithSubqueryClause with, Set<String> out) {
        if (with == null || with.getEntries() == null) return;
        for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
            if (entry.getAlias() != null) out.add(entry.getAlias().toLowerCase());
        }
    }

    /** 解析 db.table / table 全名 → TableRef(去反引号)。 */
    private static TableRef parseQualified(String raw) {
        String s = raw.replace("`", "").trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            return new TableRef(s.substring(0, dot), s.substring(dot + 1));
        }
        return new TableRef(null, s);
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
