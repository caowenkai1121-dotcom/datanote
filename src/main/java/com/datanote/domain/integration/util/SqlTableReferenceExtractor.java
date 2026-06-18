package com.datanote.domain.integration.util;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.JdbcConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SqlTableReferenceExtractor {

    private SqlTableReferenceExtractor() {
    }

    public static List<TableRef> extract(String sql) {
        String clean = sql == null ? "" : sql.trim();
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(clean, JdbcConstants.MYSQL);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 SQL 引用表, 已拒绝执行: " + e.getMessage(), e);
        }
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("无法解析 SQL 引用表, 已拒绝执行");
        }

        try {
            Set<String> cteNames = new HashSet<>();
            SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(JdbcConstants.MYSQL);
            for (SQLStatement statement : statements) {
                statement.accept(visitor);
                if (statement instanceof SQLSelectStatement) {
                    SQLSelect select = ((SQLSelectStatement) statement).getSelect();
                    if (select != null) {
                        collectCteNames(select.getWithSubQuery(), cteNames);
                    }
                }
            }

            List<TableRef> refs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (TableStat.Name name : visitor.getTables().keySet()) {
                String fullName = name.getName();
                if (fullName == null) {
                    continue;
                }
                String lower = fullName.toLowerCase();
                if (cteNames.contains(lower)) {
                    continue;
                }
                TableRef ref = parseQualified(fullName);
                if (ref == null) {
                    continue;
                }
                String key = (ref.db == null ? "" : ref.db.toLowerCase()) + "." + ref.table.toLowerCase();
                if (seen.add(key)) {
                    refs.add(ref);
                }
            }
            return refs;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 SQL 引用表失败, 已拒绝执行: " + e.getMessage(), e);
        }
    }

    private static void collectCteNames(SQLWithSubqueryClause with, Set<String> out) {
        if (with == null || with.getEntries() == null) {
            return;
        }
        for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
            if (entry.getAlias() != null) {
                out.add(entry.getAlias().toLowerCase());
            }
        }
    }

    private static TableRef parseQualified(String raw) {
        String s = raw.replace("`", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            return new TableRef(s.substring(0, dot), s.substring(dot + 1));
        }
        return new TableRef(null, s);
    }

    public static final class TableRef {
        private final String db;
        private final String table;

        private TableRef(String db, String table) {
            this.db = db;
            this.table = table;
        }

        public String getDb() {
            return db;
        }

        public String getTable() {
            return table;
        }
    }
}
