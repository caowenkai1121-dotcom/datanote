package com.datanote.service;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.DbType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询期 SQL 脱敏改写器 —— 纯函数（无 Spring 依赖，便于单测）。
 *
 * 输入：原始 SQL + 当前用户可见性（哪些库.表.列需脱敏及脱敏函数、按表的行过滤片段）。
 * 输出：改写后 SQL（敏感列 SELECT 项包裹脱敏表达式、命中表按行策略 AND 注入 WHERE）。
 *
 * 保守原则：
 *  - 仅改写 SELECT 查询；非 SELECT（DDL/INSERT/...）一律原样返回。
 *  - 无命中策略 → 原样返回（不重格式化）。
 *  - SELECT * 且该表有脱敏策略 → 无法精确改写，抛 {@link MaskRewriteException}（降级，由调用方 fail-closed）。
 *  - 解析失败且存在策略 → 抛 {@link MaskRewriteException}（fail-closed）；无策略则原样返回。
 */
public final class SqlMaskRewriter {

    private SqlMaskRewriter() {}

    private static final DbType DB_TYPE = DbType.mysql;

    /** 改写降级/失败异常：受限用户应据此 fail-closed 拒绝执行。 */
    public static final class MaskRewriteException extends Exception {
        public MaskRewriteException(String message) { super(message); }
    }

    /** 列脱敏项：库.表.列 → 脱敏函数(MASK/HASH/REPLACE/RANGE)。 */
    public static final class ColumnMask {
        private final String db;
        private final String table;
        private final String column;
        private final String func;
        public ColumnMask(String db, String table, String column, String func) {
            this.db = db; this.table = table; this.column = column; this.func = func;
        }
        public String getDb() { return db; }
        public String getTable() { return table; }
        public String getColumn() { return column; }
        public String getFunc() { return func; }
    }

    /** 行过滤项：库.表 → WHERE 片段。 */
    public static final class RowFilter {
        private final String db;
        private final String table;
        private final String filter;
        public RowFilter(String db, String table, String filter) {
            this.db = db; this.table = table; this.filter = filter;
        }
        public String getDb() { return db; }
        public String getTable() { return table; }
        public String getFilter() { return filter; }
    }

    /** 内部：表引用（库可空，由 defaultDb 补全），大小写不敏感比较。 */
    private static final class TableRef {
        final String db;
        final String table;
        TableRef(String db, String table) { this.db = db == null ? "" : db; this.table = table == null ? "" : table; }
        boolean sameAs(String d, String t) {
            return this.db.equalsIgnoreCase(d == null ? "" : d)
                    && this.table.equalsIgnoreCase(t == null ? "" : t);
        }
    }

    /**
     * 改写入口。
     *
     * @param sql          原始 SQL
     * @param defaultDb    默认库（表未带库名时补全）
     * @param columnMasks  当前用户需脱敏的列清单
     * @param rowFilters   当前用户的行过滤清单
     * @return 改写后 SQL；无命中或非 SELECT 时返回原 SQL
     * @throws MaskRewriteException SELECT * 降级 / 解析失败（存在策略时）
     */
    public static String rewrite(String sql, String defaultDb,
                                 List<ColumnMask> columnMasks,
                                 List<RowFilter> rowFilters) throws MaskRewriteException {
        boolean hasPolicy = (columnMasks != null && !columnMasks.isEmpty())
                || (rowFilters != null && !rowFilters.isEmpty());
        // 无任何策略：no-op，原样返回（不进解析，避免重格式化与误判）
        if (!hasPolicy || sql == null || sql.trim().isEmpty()) {
            return sql;
        }
        String dft = defaultDb == null ? "" : defaultDb.trim();

        List<SQLStatement> stmts;
        try {
            stmts = SQLUtils.parseStatements(sql, DB_TYPE);
        } catch (Exception e) {
            // 存在策略却解析失败 → fail-closed
            throw new MaskRewriteException("SQL 解析失败，受限用户拒绝执行: " + e.getMessage());
        }
        // 仅处理单条 SELECT；其它（多条/非 SELECT）原样放行
        if (stmts.size() != 1 || !(stmts.get(0) instanceof SQLSelectStatement)) {
            return sql;
        }

        SQLSelectStatement selectStmt = (SQLSelectStatement) stmts.get(0);
        SQLSelect select = selectStmt.getSelect();
        SQLSelectQueryBlock block = select == null ? null : select.getQueryBlock();
        if (block == null) {
            // UNION 等复杂结构暂不支持精确改写；存在策略时保守 fail-closed
            throw new MaskRewriteException("不支持的查询结构，受限用户拒绝执行");
        }

        // 建立 别名/表名 → TableRef 映射
        Map<String, TableRef> aliasMap = new HashMap<>();
        List<TableRef> fromTables = new ArrayList<>();
        collectTables(block.getFrom(), dft, aliasMap, fromTables);

        boolean changed = false;
        // 1) 列脱敏
        if (columnMasks != null && !columnMasks.isEmpty()) {
            changed |= maskColumns(block, aliasMap, fromTables, columnMasks);
        }
        // 2) 行过滤注入
        if (rowFilters != null && !rowFilters.isEmpty()) {
            changed |= injectRowFilters(block, fromTables, rowFilters);
        }

        if (!changed) {
            return sql; // 涉及的表均未命中策略 → 原样
        }
        return SQLUtils.toSQLString(selectStmt, DB_TYPE);
    }

    // ========== 列脱敏 ==========

    private static boolean maskColumns(SQLSelectQueryBlock block, Map<String, TableRef> aliasMap,
                                       List<TableRef> fromTables, List<ColumnMask> masks)
            throws MaskRewriteException {
        List<SQLSelectItem> items = block.getSelectList();
        if (items == null || items.isEmpty()) return false;

        TableRef soleSource = fromTables.size() == 1 ? fromTables.get(0) : null;
        boolean changed = false;

        for (SQLSelectItem item : items) {
            SQLExpr expr = item.getExpr();

            // SELECT *：若涉及的表存在脱敏策略，无法精确改写 → 降级 fail-closed
            if (expr instanceof SQLAllColumnExpr || isStar(expr)) {
                if (anyMaskHitsFromTables(fromTables, masks)) {
                    throw new MaskRewriteException("SELECT * 无法精确脱敏敏感列，受限用户拒绝执行");
                }
                continue; // 该表无敏感列，* 保留
            }

            String column;
            TableRef srcTab;
            if (expr instanceof SQLPropertyExpr) {
                SQLPropertyExpr pe = (SQLPropertyExpr) expr;
                column = pe.getName();
                String owner = pe.getOwnernName();
                // owner 可能是表别名或 *.（t.*）
                if ("*".equals(column)) {
                    if (anyMaskHitsFromTables(fromTables, masks)) {
                        throw new MaskRewriteException("SELECT t.* 无法精确脱敏敏感列，受限用户拒绝执行");
                    }
                    continue;
                }
                srcTab = owner == null ? soleSource : aliasMap.get(owner.toLowerCase());
            } else if (expr instanceof SQLIdentifierExpr) {
                column = ((SQLIdentifierExpr) expr).getName();
                srcTab = soleSource;
            } else {
                continue; // 表达式/函数/常量等不脱敏
            }
            if (column == null || srcTab == null) continue;

            ColumnMask hit = findMask(srcTab, column, masks);
            if (hit == null) continue;

            // 别名优先用原 SELECT item 别名，否则用原列名，保证结果集列名不变
            String alias = item.getAlias();
            if (alias == null || alias.isEmpty()) alias = column;

            SQLExpr maskedExpr = buildMaskExpr(qualifiedColumn(expr, column), hit.getFunc());
            item.setExpr(maskedExpr);
            item.setAlias(alias);
            changed = true;
        }
        return changed;
    }

    /** 生成脱敏 SQL 表达式（Doris/MySQL 通用函数），colExpr 为列引用文本（可含表别名限定）。 */
    private static SQLExpr buildMaskExpr(String colExpr, String func) {
        String f = func == null ? "MASK" : func.trim().toUpperCase();
        String text;
        switch (f) {
            case "HASH":
                text = "MD5(" + colExpr + ")";
                break;
            case "REPLACE":
                text = "'***'";
                break;
            case "RANGE":
                // 数值分桶：FLOOR(col/10)*10，尽力区间；非数值列由数据库处理或报错
                text = "CONCAT(CAST(FLOOR(" + colExpr + "/10)*10 AS CHAR),'-',"
                        + "CAST(FLOOR(" + colExpr + "/10)*10+9 AS CHAR))";
                break;
            case "MASK":
            default:
                // 保留前 3 后 4，中间掩码；短串则整体掩码兜底
                text = "CONCAT(LEFT(" + colExpr + ",3),'****',RIGHT(" + colExpr + ",4))";
                break;
        }
        return SQLUtils.toSQLExpr(text, DB_TYPE);
    }

    /** 还原列引用文本：t.col 保留限定，col 用反引号包裹避免关键字冲突。 */
    private static String qualifiedColumn(SQLExpr expr, String column) {
        if (expr instanceof SQLPropertyExpr) {
            String owner = ((SQLPropertyExpr) expr).getOwnernName();
            if (owner != null && !owner.isEmpty()) {
                return owner + ".`" + column + "`";
            }
        }
        return "`" + column + "`";
    }

    private static ColumnMask findMask(TableRef tab, String column, List<ColumnMask> masks) {
        for (ColumnMask m : masks) {
            if (tab.sameAs(m.getDb(), m.getTable()) && column.equalsIgnoreCase(m.getColumn())) {
                return m;
            }
        }
        return null;
    }

    private static boolean anyMaskHitsFromTables(List<TableRef> fromTables, List<ColumnMask> masks) {
        for (TableRef t : fromTables) {
            for (ColumnMask m : masks) {
                if (t.sameAs(m.getDb(), m.getTable())) return true;
            }
        }
        return false;
    }

    private static boolean isStar(SQLExpr expr) {
        return expr instanceof SQLAllColumnExpr;
    }

    // ========== 行过滤注入 ==========

    private static boolean injectRowFilters(SQLSelectQueryBlock block, List<TableRef> fromTables,
                                            List<RowFilter> rowFilters) throws MaskRewriteException {
        boolean changed = false;
        for (RowFilter rf : rowFilters) {
            if (rf.getFilter() == null || rf.getFilter().trim().isEmpty()) continue;
            boolean tableInFrom = false;
            for (TableRef t : fromTables) {
                if (t.sameAs(rf.getDb(), rf.getTable())) { tableInFrom = true; break; }
            }
            if (!tableInFrom) continue;

            SQLExpr cond;
            try {
                cond = SQLUtils.toSQLExpr("(" + rf.getFilter() + ")", DB_TYPE);
            } catch (Exception e) {
                throw new MaskRewriteException("行过滤条件非法，受限用户拒绝执行: " + rf.getFilter());
            }
            SQLExpr where = block.getWhere();
            if (where == null) {
                block.setWhere(cond);
            } else {
                block.setWhere(new SQLBinaryOpExpr(where, SQLBinaryOperator.BooleanAnd, cond, DB_TYPE));
            }
            changed = true;
        }
        return changed;
    }

    // ========== from 表收集 ==========

    private static void collectTables(SQLTableSource from, String dft,
                                      Map<String, TableRef> aliasMap, List<TableRef> out) {
        if (from == null) return;
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource ets = (SQLExprTableSource) from;
            String raw = ets.getExpr() == null ? null : ets.getExpr().toString();
            TableRef ref = parseQualified(raw, dft);
            if (ref != null) {
                out.add(ref);
                if (ets.getAlias() != null && !ets.getAlias().isEmpty()) {
                    aliasMap.put(ets.getAlias().toLowerCase(), ref);
                }
                aliasMap.put(ref.table.toLowerCase(), ref);
            }
        } else if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            collectTables(join.getLeft(), dft, aliasMap, out);
            collectTables(join.getRight(), dft, aliasMap, out);
        }
        // 子查询等其它表源不参与精确列定位（裸列将无法归属，安全跳过）
    }

    private static TableRef parseQualified(String raw, String dft) {
        if (raw == null) return null;
        String s = raw.replace("`", "").trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            return new TableRef(s.substring(0, dot), s.substring(dot + 1));
        }
        return new TableRef(dft, s);
    }
}
