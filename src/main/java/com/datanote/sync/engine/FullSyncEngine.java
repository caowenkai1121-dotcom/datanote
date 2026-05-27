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

        String writeSql = WriteSqlBuilder.build(target.getDatabaseType(), ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                tgtColumns, java.util.Collections.singletonList(fm.pkTarget));

        ctx.log("INFO", "开始全量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，列数=" + srcColumns.size() + "，主键=" + pkColumn);

        Object cursor = null;
        boolean hasCursor = false;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);

            while (!ctx.getStopped().get()) {
                String pageSql = MysqlConnector.buildKeysetPageSql(
                        srcDb, tc.getSourceTable(), srcColumns, pkColumn, hasCursor);

                int rowsThisPage = 0;
                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    int paramIdx = 1;
                    if (hasCursor) {
                        readPs.setObject(paramIdx++, cursor);
                    }
                    readPs.setInt(paramIdx, ctx.getBatchSize());

                    try (ResultSet rs = readPs.executeQuery();
                         PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                        while (rs.next()) {
                            // 读列与写列一一对应：按读列顺序取、按写列顺序写
                            for (int i = 0; i < srcColumns.size(); i++) {
                                writePs.setObject(i + 1, rs.getObject(i + 1));
                            }
                            writePs.addBatch();
                            cursor = rs.getObject(pkIndex + 1);
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
                            // 逐行计数：避免 ON DUPLICATE KEY UPDATE 下 affected rows 语义导致偏大
                            ctx.getWriteCount().addAndGet(rowsThisPage);
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

        ctx.log("INFO", "完成全量同步 " + tc.getSourceTable() + "，共 " + tableRead + " 行");
    }
}
