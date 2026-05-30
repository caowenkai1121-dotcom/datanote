package com.datanote.service;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.util.JdbcConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL 血缘解析器 — 纯函数。用 Druid 解析 SQL，抽取写目标表、读源表、列级 best-effort 映射。
 *
 * 支持 INSERT...SELECT / CREATE TABLE AS SELECT / 子查询 / 多 JOIN / CTE(WITH)。
 * 解析失败或无写目标时降级（返回空结果），绝不抛异常。
 */
public final class SqlLineageParser {

    private SqlLineageParser() {}

    /** 表引用：库.表（库可空，由 defaultDb 补全）。 */
    public static final class TableRef {
        private final String db;
        private final String table;
        public TableRef(String db, String table) { this.db = db; this.table = table; }
        public String getDb() { return db; }
        public String getTable() { return table; }
        public String full() { return db + "." + table; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof TableRef)) return false;
            TableRef t = (TableRef) o;
            return full().equalsIgnoreCase(t.full());
        }
        @Override public int hashCode() { return full().toLowerCase().hashCode(); }
    }

    /** 列级映射：目标列 ← 源库.源表.源列。 */
    public static final class ColumnMapping {
        private final String targetColumn;
        private final String srcDb;
        private final String srcTable;
        private final String srcColumn;
        public ColumnMapping(String targetColumn, String srcDb, String srcTable, String srcColumn) {
            this.targetColumn = targetColumn; this.srcDb = srcDb; this.srcTable = srcTable; this.srcColumn = srcColumn;
        }
        public String getTargetColumn() { return targetColumn; }
        public String getSrcDb() { return srcDb; }
        public String getSrcTable() { return srcTable; }
        public String getSrcColumn() { return srcColumn; }
    }

    /** 解析结果。 */
    public static final class ParseResult {
        private TableRef writeTable;
        private final List<TableRef> readTables = new ArrayList<>();
        private final List<ColumnMapping> columnMappings = new ArrayList<>();
        public TableRef getWriteTable() { return writeTable; }
        public List<TableRef> getReadTables() { return readTables; }
        public List<ColumnMapping> getColumnMappings() { return columnMappings; }
    }

    /**
     * 解析单条 SQL。
     * @param sql        SQL 文本
     * @param defaultDb  默认库（表未带库名时补全）
     * @return 解析结果（失败降级为空，不抛异常）
     */
    public static ParseResult parse(String sql, String defaultDb) {
        ParseResult result = new ParseResult();
        if (sql == null || sql.trim().isEmpty()) return result;
        String dft = defaultDb == null ? "" : defaultDb.trim();
        try {
            List<SQLStatement> stmts = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
            for (SQLStatement stmt : stmts) {
                if (stmt instanceof SQLInsertStatement) {
                    handleInsert((SQLInsertStatement) stmt, dft, result);
                } else if (stmt instanceof SQLCreateTableStatement) {
                    handleCreateTableAsSelect((SQLCreateTableStatement) stmt, dft, result);
                }
                // 仅处理写入型语句；纯 SELECT 无写目标，返回空写目标。
                if (result.writeTable != null) break;
            }
        } catch (Exception e) {
            // 解析失败：降级为空结果。
            return new ParseResult();
        }
        return result;
    }

    private static void handleInsert(SQLInsertStatement insert, String dft, ParseResult result) {
        result.writeTable = toRef(insert.getTableName(), dft);
        SQLSelect select = insert.getQuery();
        if (select == null) return;
        collectFromSelect(select, dft, result);
        buildColumnMappings(insert.getColumns(), select, result);
    }

    private static void handleCreateTableAsSelect(SQLCreateTableStatement create, String dft, ParseResult result) {
        SQLSelect select = create.getSelect();
        if (select == null) return; // 普通建表无血缘
        result.writeTable = toRef(create.getName(), dft);
        collectFromSelect(select, dft, result);
        buildColumnMappings(null, select, result);
    }

    // ========== 读源表收集（遍历 AST，剔除 CTE 名与子查询别名） ==========

    private static void collectFromSelect(SQLSelect select, String dft, ParseResult result) {
        Set<String> cteNames = new HashSet<>();
        SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(JdbcConstants.MYSQL);
        select.accept(visitor);
        collectCteNames(select.getWithSubQuery(), cteNames);

        Set<String> seen = new HashSet<>();
        for (com.alibaba.druid.stat.TableStat.Name name : visitor.getTables().keySet()) {
            String full = name.getName(); // 可能形如 db.table 或 table
            String lower = full.toLowerCase();
            // CTE 名不是物理表，剔除
            if (cteNames.contains(lower)) continue;
            TableRef ref = parseQualifiedName(full, dft);
            if (ref == null) continue;
            // 排除写目标自身
            if (result.writeTable != null && ref.equals(result.writeTable)) continue;
            if (seen.add(ref.full().toLowerCase())) {
                result.readTables.add(ref);
            }
        }
    }

    private static void collectCteNames(SQLWithSubqueryClause with, Set<String> out) {
        if (with == null || with.getEntries() == null) return;
        for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
            if (entry.getAlias() != null) out.add(entry.getAlias().toLowerCase());
        }
    }

    // ========== 列级 best-effort 映射 ==========

    private static void buildColumnMappings(List<SQLExpr> insertColumns, SQLSelect select, ParseResult result) {
        SQLSelectQueryBlock block = select.getQueryBlock();
        if (block == null || block.getSelectList() == null) return;
        List<SQLSelectItem> items = block.getSelectList();

        // 建立 别名/表名 → TableRef 映射，用于把 t.col 定位到物理表
        Map<String, TableRef> aliasToTable = new HashMap<>();
        TableRef soleSource = collectAliasMap(block.getFrom(), result, aliasToTable);

        for (int i = 0; i < items.size(); i++) {
            SQLSelectItem item = items.get(i);
            SQLExpr expr = item.getExpr();
            String srcColumn;
            TableRef srcTab;
            if (expr instanceof SQLPropertyExpr) {
                // t.col 形式
                SQLPropertyExpr pe = (SQLPropertyExpr) expr;
                srcColumn = pe.getName();
                String owner = pe.getOwnernName();
                srcTab = owner == null ? soleSource : aliasToTable.get(owner.toLowerCase());
            } else if (expr instanceof SQLIdentifierExpr) {
                // 裸列名：仅当单一源表可确定
                srcColumn = ((SQLIdentifierExpr) expr).getName();
                srcTab = soleSource;
            } else {
                continue; // 表达式/聚合/* 等降级，不产列映射
            }
            if (srcTab == null || srcColumn == null || "*".equals(srcColumn)) continue;

            String targetColumn = resolveTargetColumn(insertColumns, item, i, srcColumn);
            if (targetColumn == null) continue;
            result.columnMappings.add(new ColumnMapping(targetColumn,
                    srcTab.getDb(), srcTab.getTable(), srcColumn));
        }
    }

    /** 收集 from 中的 表/别名 → TableRef；若只有唯一物理源表则返回它（用于裸列归属）。 */
    private static TableRef collectAliasMap(SQLTableSource from, ParseResult result, Map<String, TableRef> out) {
        if (from == null) return result.readTables.size() == 1 ? result.readTables.get(0) : null;
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource ets = (SQLExprTableSource) from;
            TableRef ref = matchReadTable(ets, result);
            if (ref != null) {
                if (ets.getAlias() != null) out.put(ets.getAlias().toLowerCase(), ref);
                out.put(ref.getTable().toLowerCase(), ref);
            }
        } else if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            collectAliasMap(join.getLeft(), result, out);
            collectAliasMap(join.getRight(), result, out);
        }
        return result.readTables.size() == 1 ? result.readTables.get(0) : null;
    }

    /** 把 from 中的表源匹配到已收集的物理读源表（按表名，忽略大小写）。 */
    private static TableRef matchReadTable(SQLExprTableSource ets, ParseResult result) {
        String name = ets.getExpr() == null ? null : ets.getExpr().toString();
        if (name == null) return null;
        String tableOnly = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        for (TableRef r : result.readTables) {
            if (r.getTable().equalsIgnoreCase(tableOnly)) return r;
        }
        return null;
    }

    /** 解析目标列名：优先 INSERT 列清单按位置；否则用 SELECT item 别名；再否则用源列名。 */
    private static String resolveTargetColumn(List<SQLExpr> insertColumns, SQLSelectItem item, int idx, String srcColumn) {
        if (insertColumns != null && idx < insertColumns.size()) {
            return plainName(insertColumns.get(idx));
        }
        if (item.getAlias() != null && !item.getAlias().isEmpty()) {
            return stripQuote(item.getAlias());
        }
        return srcColumn;
    }

    // ========== 工具 ==========

    private static TableRef toRef(SQLName name, String dft) {
        return name == null ? null : parseQualifiedName(name.toString(), dft);
    }

    private static TableRef parseQualifiedName(String raw, String dft) {
        if (raw == null) return null;
        String s = raw.replace("`", "").trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            return new TableRef(s.substring(0, dot), s.substring(dot + 1));
        }
        return new TableRef(dft, s);
    }

    private static String plainName(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) return ((SQLIdentifierExpr) expr).getName();
        if (expr instanceof SQLPropertyExpr) return ((SQLPropertyExpr) expr).getName();
        return expr == null ? null : stripQuote(expr.toString());
    }

    private static String stripQuote(String s) {
        return s == null ? null : s.replace("`", "").replace("\"", "").trim();
    }
}
