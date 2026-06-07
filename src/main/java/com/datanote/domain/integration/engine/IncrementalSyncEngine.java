package com.datanote.domain.integration.engine;

import com.datanote.domain.integration.connector.DbConnector;
import com.datanote.domain.integration.connector.MysqlConnector;
import com.datanote.domain.integration.connector.TableMeta;
import com.datanote.domain.integration.dto.SyncContext;
import com.datanote.domain.integration.dto.TableSyncConfig;
import com.datanote.domain.integration.engine.incremental.IncrementalStrategy;
import com.datanote.domain.integration.engine.incremental.IncrementalStrategyFactory;
import com.datanote.domain.integration.util.FieldMappingResolver;
import com.datanote.domain.integration.util.WriteSqlBuilder;
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
        List<String> pks = meta.getPrimaryKeys();
        if (pks.isEmpty()) {
            throw new IllegalStateException("增量同步需至少一个主键列（用作复合游标），表: " + tc.getSourceTable());
        }
        // 解析字段映射：fields 为空则读写全列、主键 target=source；非空则裁剪+重命名并校验各主键在选中列中
        FieldMappingResolver.Resolved fm = FieldMappingResolver.resolve(tc, meta.getColumns(), pks);
        List<String> srcColumns = fm.srcColumns;   // 读列（源名）
        List<String> tgtColumns = fm.tgtColumns;   // 写列（目标名），与 srcColumns 一一对应
        // 增量字段用于源端 WHERE/ORDER 与断点推进，必须在选中读列中（否则取不到值）
        int incIndex = srcColumns.indexOf(incField);
        if (incIndex < 0) {
            throw new IllegalStateException("增量字段未在选中同步字段中: " + incField
                    + "（表: " + tc.getSourceTable() + "）");
        }
        // 各主键源列在读列中的下标（复合游标取值用）
        int[] pkSourceIdx = new int[fm.pkSourceColumns.size()];
        for (int k = 0; k < pkSourceIdx.length; k++) {
            pkSourceIdx[k] = srcColumns.indexOf(fm.pkSourceColumns.get(k));
            if (pkSourceIdx[k] < 0) {
                throw new IllegalStateException("主键列不在选中同步字段中: " + fm.pkSourceColumns.get(k)
                        + "（表: " + tc.getSourceTable() + "）");
            }
        }
        String extraWhere = com.datanote.domain.integration.util.FilterExpressionBuilder.build(tc.getFilterExpression(), ctx.getSource()::quoteIdentifier);
        com.datanote.domain.integration.util.RowValueProcessor rowProc =
                new com.datanote.domain.integration.util.RowValueProcessor(fm.srcToFieldMapping);

        IncrementalStrategy strategy = IncrementalStrategyFactory.get(tc.getIncrementalType());

        // 起始断点：有则用，无则按类型给安全初值（时间戳用纪元，自增用 0）
        Object startValue = tc.getIncrementalValue();
        if (startValue == null || String.valueOf(startValue).isEmpty()) {
            startValue = "AUTO_INCREMENT".equalsIgnoreCase(tc.getIncrementalType()) ? "0" : "1970-01-01 00:00:00";
        }

        // 迭代V3：标记同步时间戳 —— 写列末尾追加 syncTsField（读列不变，绑定时最后一列绑当前时间）
        boolean markTs = SyncTsSupport.shouldAppend(ctx, tgtColumns);
        List<String> writeColumns = SyncTsSupport.appendTsColumn(tgtColumns, ctx, markTs);

        String writeSql = WriteSqlBuilder.build(target.getDatabaseType(), ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                writeColumns, fm.pkTargetColumns);
        String firstSql = ctx.getSource().incrementalPageSql(srcDb, tc.getSourceTable(), srcColumns, incField, fm.pkSourceColumns, true, extraWhere);
        String nextSql  = ctx.getSource().incrementalPageSql(srcDb, tc.getSourceTable(), srcColumns, incField, fm.pkSourceColumns, false, extraWhere);

        ctx.log("INFO", "开始增量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，增量字段=" + incField + "，主键=" + fm.pkSourceColumns + "，起始断点=" + startValue);

        Object maxValue = startValue;   // 本次见过的最大增量值（断点回写用）
        Object cursorInc = startValue;  // 复合游标：增量值
        Object[] cursorPk = null;       // 复合游标：主键值（长度=主键列数）
        boolean firstPage = true;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);
            String preSql = ctx.getPreSql(tc);
            if (preSql != null) {
                ctx.log("INFO", "执行前置SQL: " + tc.getSourceTable());
                com.datanote.domain.integration.util.SqlExecutor.execute(tgtConn, preSql);
                tgtConn.commit();
            }

            // writeSql 是循环不变量，writePs 提到分页循环外复用，保留 prepStmt 缓存与批处理重写收益
            try (PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                BatchWriter bw = new BatchWriter(writePs, tgtConn, ctx, writeColumns, tc.getSourceTable(), tc.getTargetTable());
                while (!ctx.getStopped().get()) {
                    String pageSql = firstPage ? firstSql : nextSql;
                    int rowsThisPage = 0;
                    Object lastInc = cursorInc;
                    Object[] lastPk = cursorPk;

                    try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                        if (firstPage) {
                            readPs.setObject(1, cursorInc);
                            readPs.setInt(2, ctx.getBatchSize());
                        } else {
                            readPs.setObject(1, cursorInc);
                            readPs.setObject(2, cursorInc);
                            int p = 3;
                            for (Object v : cursorPk) readPs.setObject(p++, v);
                            readPs.setInt(p, ctx.getBatchSize());
                        }
                        readPs.setFetchSize(ctx.getBatchSize()); // 流式读取，控制客户端内存峰值

                        try (ResultSet rs = readPs.executeQuery()) {
                            int rowsWritten = 0;
                            while (rs.next()) {
                                Object[] raw = new Object[srcColumns.size()];
                                for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                                lastInc = raw[incIndex];
                                Object[] curPk = new Object[pkSourceIdx.length];
                                for (int k = 0; k < pkSourceIdx.length; k++) curPk[k] = raw[pkSourceIdx[k]];
                                lastPk = curPk;
                                if (lastInc != null && strategy.compare(lastInc, maxValue) > 0) maxValue = lastInc;
                                rowsThisPage++;
                                Object[] row = rowProc.process(srcColumns, raw);
                                if (row == null) continue;  // SKIP_ROW：计读不计写，游标/断点已按原始值推进
                                Object[] writeRow = new Object[writeColumns.size()];
                                System.arraycopy(row, 0, writeRow, 0, srcColumns.size());
                                if (markTs) writeRow[srcColumns.size()] = new java.sql.Timestamp(System.currentTimeMillis());
                                bw.add(writeRow);
                                rowsWritten++;
                            }
                            bw.flush(); // 内部：批成功commit+writeCount；批失败回退逐行+阈值容错（超阈值抛 DirtyDataExceededException）
                            if (ctx.getRateLimiter() != null && rowsWritten > 0) ctx.getRateLimiter().acquire(rowsWritten);
                            if (rowsThisPage > 0) { cursorInc = lastInc; cursorPk = lastPk; }
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

            String postSql = ctx.getPostSql(tc);
            if (postSql != null) {
                ctx.log("INFO", "执行后置SQL: " + tc.getSourceTable());
                com.datanote.domain.integration.util.SqlExecutor.execute(tgtConn, postSql);
                tgtConn.commit();
            }
        }

        tc.setIncrementalValue(strategy.toStored(maxValue));
        // 该表成功即持久化断点：避免后续表失败导致本表已 commit 的数据下次从旧断点重扫
        ctx.checkpoint(tc);
        ctx.log("INFO", "完成增量同步 " + tc.getSourceTable() + "，本次 " + tableRead
                + " 行，新断点=" + tc.getIncrementalValue());
    }
}
