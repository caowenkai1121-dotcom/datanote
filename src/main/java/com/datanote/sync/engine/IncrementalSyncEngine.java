package com.datanote.sync.engine;

import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.connector.TableMeta;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.incremental.IncrementalStrategy;
import com.datanote.sync.engine.incremental.IncrementalStrategyFactory;
import com.datanote.sync.util.WriteSqlBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 增量同步引擎：按 (incrementalField, 主键) 复合游标升序分页，避免同值跨页丢数据/死循环。
 * 首页用 incField >= 断点（含断点，配合 UPSERT 幂等吸收边界重复）；后续页用复合游标严格推进。
 * 每表跟踪本次最大增量值并写回 TableSyncConfig.incrementalValue，由执行层持久化为断点。
 */
@Slf4j
public class IncrementalSyncEngine implements SyncEngine {

    @Override
    public void sync(SyncContext ctx) {
        for (TableSyncConfig tc : ctx.getTables()) {
            if (ctx.getStopped().get()) {
                ctx.log("WARN", "任务被停止，中断");
                break;
            }
            try {
                syncOneTable(ctx, tc);
            } catch (Exception e) {
                ctx.getErrorCount().incrementAndGet();
                ctx.log("ERROR", "表增量同步失败 " + tc.getSourceTable() + ": " + e.getMessage());
                log.error("表增量同步失败: {}", tc.getSourceTable(), e);
            }
        }
    }

    private void syncOneTable(SyncContext ctx, TableSyncConfig tc) throws Exception {
        DbConnector source = ctx.getSource();
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();

        if (tc.getIncrementalField() == null || tc.getIncrementalField().trim().isEmpty()) {
            throw new IllegalStateException("增量同步未配置 incrementalField，表: " + tc.getSourceTable());
        }
        String incField = tc.getIncrementalField();

        TableMeta meta = source.getTableMeta(srcDb, tc.getSourceTable());
        if (meta.getColumns().isEmpty()) {
            throw new IllegalStateException("源表无列或不存在: " + tc.getSourceTable());
        }
        if (!meta.getColumns().contains(incField)) {
            throw new IllegalStateException("增量字段不在源表列中: " + incField);
        }
        if (meta.getPrimaryKeys().size() != 1) {
            throw new IllegalStateException(
                "增量同步当前仅支持单列主键（用作复合游标），表: " + tc.getSourceTable()
                + "，主键数=" + meta.getPrimaryKeys().size());
        }
        List<String> columns = meta.getColumns();
        String pkColumn = meta.getPrimaryKeys().get(0);
        int incIndex = columns.indexOf(incField);
        int pkIndex = columns.indexOf(pkColumn);

        IncrementalStrategy strategy = IncrementalStrategyFactory.get(tc.getIncrementalType());

        // 起始断点：有则用，无则按类型给安全初值（时间戳用纪元，自增用 0）
        Object startValue = tc.getIncrementalValue();
        if (startValue == null || String.valueOf(startValue).isEmpty()) {
            startValue = "AUTO_INCREMENT".equalsIgnoreCase(tc.getIncrementalType()) ? "0" : "1970-01-01 00:00:00";
        }

        String writeSql = WriteSqlBuilder.build(ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                columns, meta.getPrimaryKeys());
        String firstSql = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), columns, incField, pkColumn, true);
        String nextSql = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), columns, incField, pkColumn, false);

        ctx.log("INFO", "开始增量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，增量字段=" + incField + "，主键=" + pkColumn + "，起始断点=" + startValue);

        Object maxValue = startValue;   // 本次见过的最大增量值（断点回写用）
        Object cursorInc = startValue;  // 复合游标：增量值
        Object cursorPk = null;         // 复合游标：主键值
        boolean firstPage = true;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);

            while (!ctx.getStopped().get()) {
                String pageSql = firstPage ? firstSql : nextSql;
                int rowsThisPage = 0;
                Object lastInc = cursorInc;
                Object lastPk = cursorPk;

                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    if (firstPage) {
                        readPs.setObject(1, cursorInc);
                        readPs.setInt(2, ctx.getBatchSize());
                    } else {
                        readPs.setObject(1, cursorInc);
                        readPs.setObject(2, cursorInc);
                        readPs.setObject(3, cursorPk);
                        readPs.setInt(4, ctx.getBatchSize());
                    }

                    try (ResultSet rs = readPs.executeQuery();
                         PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                        while (rs.next()) {
                            for (int i = 0; i < columns.size(); i++) {
                                writePs.setObject(i + 1, rs.getObject(i + 1));
                            }
                            writePs.addBatch();
                            lastInc = rs.getObject(incIndex + 1);
                            lastPk = rs.getObject(pkIndex + 1);
                            if (lastInc != null && strategy.compare(lastInc, maxValue) > 0) {
                                maxValue = lastInc;
                            }
                            rowsThisPage++;
                        }
                        if (rowsThisPage > 0) {
                            try {
                                writePs.executeBatch();
                                tgtConn.commit();
                            } catch (Exception batchEx) {
                                tgtConn.rollback();
                                throw batchEx;
                            }
                            ctx.getWriteCount().addAndGet(rowsThisPage);
                            cursorInc = lastInc;
                            cursorPk = lastPk;
                        }
                    }
                }

                tableRead += rowsThisPage;
                ctx.getReadCount().addAndGet(rowsThisPage);
                firstPage = false;

                if (rowsThisPage < ctx.getBatchSize()) {
                    break;
                }
            }
        }

        tc.setIncrementalValue(strategy.toStored(maxValue));
        ctx.log("INFO", "完成增量同步 " + tc.getSourceTable() + "，本次 " + tableRead
                + " 行，新断点=" + tc.getIncrementalValue());
    }
}
