package com.datanote.sync.engine;

import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.connector.TableMeta;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.FieldMappingResolver;
import com.datanote.sync.util.WriteSqlBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 全量同步引擎：按单列主键 keyset 游标分页读源表，批量 upsert 写目标表。
 * 读写 SQL 均带库全限定前缀，不依赖连接默认库。单表失败不中断其他表。
 */
@Slf4j
public class FullSyncEngine implements SyncEngine {

    /**
     * 执行全量同步（遍历 ctx.tables）。单表失败仅计数并记录，不中断后续表。
     */
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
                ctx.log("ERROR", "表同步失败 " + tc.getSourceTable() + ": " + e.getMessage());
                log.error("表同步失败: {}", tc.getSourceTable(), e);
                // 单表失败不中断其他表：错误已计数，执行层据 errorCount 判定整体状态
            }
        }
    }

    private void syncOneTable(SyncContext ctx, TableSyncConfig tc) throws Exception {
        DbConnector source = ctx.getSource();
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();

        TableMeta meta = source.getTableMeta(srcDb, tc.getSourceTable());
        if (meta.getColumns().isEmpty()) {
            throw new IllegalStateException("源表无列或不存在: " + tc.getSourceTable());
        }
        if (meta.getPrimaryKeys().size() != 1) {
            throw new IllegalStateException(
                "全量同步当前仅支持单列主键（计划1限制），表: " + tc.getSourceTable()
                + "，主键数=" + meta.getPrimaryKeys().size());
        }
        String pkColumn = meta.getPrimaryKeys().get(0);
        // 解析字段映射：fields 为空则读写均为全列、主键 target=source；非空则裁剪+重命名并校验主键在选中列中
        FieldMappingResolver.Resolved fm = FieldMappingResolver.resolve(tc, meta.getColumns(), pkColumn);
        List<String> srcColumns = fm.srcColumns;   // 读列（源名）
        List<String> tgtColumns = fm.tgtColumns;   // 写列（目标名），与 srcColumns 一一对应
        int pkIndex = srcColumns.indexOf(pkColumn); // 主键在读列中的位置（keyset 游标用）
        if (pkIndex < 0) {
            throw new IllegalStateException("主键列不在列集合中: " + pkColumn);
        }
        String extraWhere = com.datanote.sync.util.FilterExpressionBuilder.build(tc.getFilterExpression());
        com.datanote.sync.util.RowValueProcessor rowProc =
                new com.datanote.sync.util.RowValueProcessor(fm.srcToFieldMapping);

        // 迭代V3：标记同步时间戳 —— 写列在 tgtColumns 末尾追加 syncTsField（读列不变，绑定时最后一列绑当前时间）
        boolean markTs = SyncTsSupport.shouldAppend(ctx, tgtColumns);
        List<String> writeColumns = SyncTsSupport.appendTsColumn(tgtColumns, ctx, markTs);

        String writeSql = WriteSqlBuilder.build(target.getDatabaseType(), ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                writeColumns, java.util.Collections.singletonList(fm.pkTarget));

        ctx.log("INFO", "开始全量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，列数=" + srcColumns.size() + "，主键=" + pkColumn
                + (markTs ? "，标记同步时间戳=" + ctx.getSyncTsField() : ""));

        Object cursor = null;
        boolean hasCursor = false;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);
            String preSql = ctx.getPreSql(tc);
            if (preSql != null) {
                ctx.log("INFO", "执行前置SQL: " + tc.getSourceTable());
                com.datanote.sync.util.SqlExecutor.execute(tgtConn, preSql);
                tgtConn.commit();
            }

            // writeSql 是循环不变量，writePs 提到分页循环外复用，保留 prepStmt 缓存与批处理重写收益
            try (PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                while (!ctx.getStopped().get()) {
                    String pageSql = MysqlConnector.buildKeysetPageSql(
                            srcDb, tc.getSourceTable(), srcColumns, pkColumn, hasCursor, extraWhere);

                    int rowsThisPage = 0;
                    try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                        int paramIdx = 1;
                        if (hasCursor) {
                            readPs.setObject(paramIdx++, cursor);
                        }
                        readPs.setInt(paramIdx, ctx.getBatchSize());
                        readPs.setFetchSize(ctx.getBatchSize()); // 流式读取，控制客户端内存峰值

                        try (ResultSet rs = readPs.executeQuery()) {
                            int rowsWritten = 0;
                            while (rs.next()) {
                                Object[] raw = new Object[srcColumns.size()];
                                for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                                cursor = raw[pkIndex];      // 游标按原始主键推进（即使跳行也推进）
                                rowsThisPage++;
                                Object[] row = rowProc.process(srcColumns, raw);
                                if (row == null) continue;  // SKIP_ROW：计读不计写
                                for (int i = 0; i < srcColumns.size(); i++) writePs.setObject(i + 1, row[i]);
                                if (markTs) {
                                    writePs.setObject(srcColumns.size() + 1,
                                            new java.sql.Timestamp(System.currentTimeMillis()));
                                }
                                writePs.addBatch();
                                rowsWritten++;
                            }
                            if (rowsWritten > 0) {
                                try {
                                    writePs.executeBatch();
                                    tgtConn.commit();
                                } catch (Exception batchEx) {
                                    tgtConn.rollback();
                                    throw batchEx;
                                } finally {
                                    writePs.clearBatch();
                                }
                                ctx.getWriteCount().addAndGet(rowsWritten);
                            }
                        }
                    }

                    tableRead += rowsThisPage;
                    ctx.getReadCount().addAndGet(rowsThisPage);
                    hasCursor = true;

                    if (rowsThisPage > 0) {
                        ctx.log("INFO", tc.getSourceTable() + " 已读 " + tableRead + " 行");
                    }
                    if (rowsThisPage < ctx.getBatchSize()) {
                        break; // 最后一页
                    }
                }
            }

            String postSql = ctx.getPostSql(tc);
            if (postSql != null) {
                ctx.log("INFO", "执行后置SQL: " + tc.getSourceTable());
                com.datanote.sync.util.SqlExecutor.execute(tgtConn, postSql);
                tgtConn.commit();
            }
        }

        ctx.log("INFO", "完成全量同步 " + tc.getSourceTable() + "，共 " + tableRead + " 行");
    }
}
