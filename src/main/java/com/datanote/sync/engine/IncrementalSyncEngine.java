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
 * 增量同步引擎：按 incrementalField 升序分页（游标=该字段值，初值=断点），批量 upsert 写目标。
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
        List<String> columns = meta.getColumns();
        int incIndex = columns.indexOf(incField);

        IncrementalStrategy strategy = IncrementalStrategyFactory.get(tc.getIncrementalType());

        Object lastValue = tc.getIncrementalValue();
        if (lastValue == null || String.valueOf(lastValue).isEmpty()) {
            lastValue = "AUTO_INCREMENT".equalsIgnoreCase(tc.getIncrementalType()) ? "0" : "";
        }

        String writeSql = WriteSqlBuilder.build(ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                columns, meta.getPrimaryKeys());
        String pageSql = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), columns, incField);

        ctx.log("INFO", "开始增量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，增量字段=" + incField + "，起始断点=" + lastValue);

        Object maxValue = lastValue;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);

            while (!ctx.getStopped().get()) {
                int rowsThisPage = 0;
                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    readPs.setObject(1, maxValue);
                    readPs.setInt(2, ctx.getBatchSize());

                    try (ResultSet rs = readPs.executeQuery();
                         PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                        Object pageMax = maxValue;
                        while (rs.next()) {
                            for (int i = 0; i < columns.size(); i++) {
                                writePs.setObject(i + 1, rs.getObject(i + 1));
                            }
                            writePs.addBatch();
                            Object cur = rs.getObject(incIndex + 1);
                            if (cur != null && strategy.compare(cur, pageMax) > 0) {
                                pageMax = cur;
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
                            maxValue = pageMax;
                        }
                    }
                }

                tableRead += rowsThisPage;
                ctx.getReadCount().addAndGet(rowsThisPage);

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
