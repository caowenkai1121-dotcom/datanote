package com.datanote.domain.integration.engine;

import com.datanote.domain.integration.connector.DbConnector;
import com.datanote.domain.integration.connector.MysqlConnector;
import com.datanote.domain.integration.connector.TableMeta;
import com.datanote.domain.integration.dto.SyncContext;
import com.datanote.domain.integration.dto.TableSyncConfig;
import com.datanote.domain.integration.util.FieldMappingResolver;
import com.datanote.domain.integration.util.WriteSqlBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 全量同步引擎：按主键 keyset 游标分页读源表，批量 upsert 写目标表。
 * 复合主键用行值游标分页并支持 chunk 续传；无主键降级流式 INSERT（不分页/不去重/不续传）。
 * 读写 SQL 均带库全限定前缀，不依赖连接默认库。单表失败不中断其他表。
 */
@Slf4j
public class FullSyncEngine implements SyncEngine {

    /**
     * 执行全量同步（遍历 ctx.tables）。单表失败仅计数并记录，不中断后续表。
     */
    @Override
    public void sync(SyncContext ctx) {
        List<TableSyncConfig> tables = ctx.getTables();
        int par = Math.max(1, ctx.getTableParallel());
        if (par <= 1 || tables.size() <= 1) {   // 默认: 顺序(零行为变更)
            for (TableSyncConfig tc : tables) {
                if (ctx.getStopped().get()) { ctx.log("WARN", "任务被停止，中断"); break; }
                syncOneTableSafe(ctx, tc);
            }
            return;
        }
        // 大任务: 多表有界并行(受 HikariCP 池约束, 每表占源+目标各1连接; 单表失败已隔离计数不中断其他表)
        int threads = Math.min(par, tables.size());
        ctx.log("INFO", "多表并行同步: 并行度=" + threads + ", 表数=" + tables.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                threads, r -> { Thread t = new Thread(r, "sync-table"); t.setDaemon(true); return t; });
        try {
            java.util.List<java.util.concurrent.Future<?>> futs = new java.util.ArrayList<>();
            for (TableSyncConfig tc : tables) {
                futs.add(pool.submit(() -> { if (!ctx.getStopped().get()) syncOneTableSafe(ctx, tc); }));
            }
            for (java.util.concurrent.Future<?> f : futs) {
                try { f.get(); } catch (Exception e) { /* 单表错误已在 syncOneTableSafe 内计数, 此处吞 Future 包装异常 */ }
            }
        } finally {
            pool.shutdown();
        }
    }

    /** 单表同步并隔离错误(顺序/并行共用): 失败仅计数+记录, 不中断其他表。 */
    private void syncOneTableSafe(SyncContext ctx, TableSyncConfig tc) {
        try {
            syncOneTable(ctx, tc);
        } catch (Exception e) {
            ctx.getErrorCount().incrementAndGet();
            ctx.log("ERROR", "表同步失败 " + tc.getSourceTable() + ": " + e.getMessage());
            log.error("表同步失败: {}", tc.getSourceTable(), e);
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
        List<String> pks = meta.getPrimaryKeys();
        // 解析字段映射：fields 为空则读写均为全列、主键 target=source；非空则裁剪+重命名并校验各主键在选中列中
        FieldMappingResolver.Resolved fm = FieldMappingResolver.resolve(tc, meta.getColumns(), pks);
        List<String> tgtColumns = fm.tgtColumns;   // 写列（目标名），与 srcColumns 一一对应

        String extraWhere = com.datanote.domain.integration.util.FilterExpressionBuilder.build(tc.getFilterExpression(), ctx.getSource()::quoteIdentifier);

        // 迭代V3：标记同步时间戳 —— 写列在 tgtColumns 末尾追加 syncTsField（读列不变，绑定时最后一列绑当前时间）
        boolean markTs = SyncTsSupport.shouldAppend(ctx, tgtColumns);
        List<String> writeColumns = SyncTsSupport.appendTsColumn(tgtColumns, ctx, markTs);

        ctx.log("INFO", "开始全量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，列数=" + fm.srcColumns.size() + "，主键=" + pks
                + (markTs ? "，标记同步时间戳=" + ctx.getSyncTsField() : ""));

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);
            String preSql = ctx.getPreSql(tc);
            if (preSql != null) {
                ctx.log("INFO", "执行前置SQL: " + tc.getSourceTable());
                com.datanote.domain.integration.util.SqlExecutor.execute(tgtConn, preSql);
                tgtConn.commit();
            }

            long tableRead = pks.isEmpty()
                    ? syncByStreaming(ctx, tc, fm, srcConn, tgtConn, writeColumns, markTs, extraWhere)
                    : syncByKeyset(ctx, tc, fm, srcConn, tgtConn, writeColumns, markTs, extraWhere);

            String postSql = ctx.getPostSql(tc);
            if (postSql != null) {
                ctx.log("INFO", "执行后置SQL: " + tc.getSourceTable());
                com.datanote.domain.integration.util.SqlExecutor.execute(tgtConn, postSql);
                tgtConn.commit();
            }

            ctx.log("INFO", "完成全量同步 " + tc.getSourceTable() + "，共 " + tableRead + " 行");
        }
    }

    /** 单/复合主键 keyset 分页（含 chunk 续传）。返回本表读取行数。 */
    private long syncByKeyset(SyncContext ctx, TableSyncConfig tc, FieldMappingResolver.Resolved fm,
                              Connection srcConn, Connection tgtConn,
                              List<String> writeColumns, boolean markTs, String extraWhere) throws Exception {
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();
        List<String> srcColumns = fm.srcColumns;
        com.datanote.domain.integration.util.RowValueProcessor rowProc =
                new com.datanote.domain.integration.util.RowValueProcessor(fm.srcToFieldMapping);

        String writeSql = WriteSqlBuilder.build(target.getDatabaseType(), ctx.getWriteMode(), tgtDb,
                tc.getTargetTable(), writeColumns, fm.pkTargetColumns);

        // 各主键源列在读列中的下标（游标取值用）
        int[] pkSourceIdx = new int[fm.pkSourceColumns.size()];
        for (int k = 0; k < pkSourceIdx.length; k++) {
            pkSourceIdx[k] = srcColumns.indexOf(fm.pkSourceColumns.get(k));
            if (pkSourceIdx[k] < 0) {
                throw new IllegalStateException("主键列不在列集合中: " + fm.pkSourceColumns.get(k));
            }
        }

        // chunk 续传游标以字符串数组序列化,绑定时靠 MySQL 隐式转型;非 MySQL 源(PG/Oracle/SQLServer)对
        // 'pk > ?' 绑字符串会类型不匹配报错或语义错误,故仅 MySQL 源支持 chunk 续传,其余降级为整表重扫(UPSERT 幂等)。
        boolean mysqlSource = "MYSQL".equalsIgnoreCase(ctx.getSource().getDatabaseType());
        boolean nonMysqlWarned = false;

        Object[] cursor = null;
        boolean hasCursor = false;
        // chunk 载入：有断点则从游标继续（仅 MySQL 源；非 MySQL 源忽略历史游标，整表重扫）
        String cj = mysqlSource ? ctx.getChunkLoad().apply(tc.getSourceTable()) : null;
        if (cj != null && !cj.isEmpty()) {
            java.util.List<Object> a = com.alibaba.fastjson2.JSON.parseArray(cj, Object.class);
            cursor = a.toArray();
            hasCursor = true;
            ctx.log("INFO", "chunk续传，从游标继续: " + tc.getSourceTable());
        }

        long tableRead = 0;
        int batchSize = SyncEngine.adaptiveBatch(ctx, srcDb, tc.getSourceTable(), srcConn);   // 大表(>千万/亿)自适应调大批次, 减往返
        boolean binaryWarned = false;
        try (PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
            BatchWriter bw = new BatchWriter(writePs, tgtConn, ctx, writeColumns, tc.getSourceTable(), tc.getTargetTable());
            while (!ctx.getStopped().get()) {
                String pageSql = ctx.getSource().keysetPageSql(
                        srcDb, tc.getSourceTable(), srcColumns, fm.pkSourceColumns, hasCursor, extraWhere);

                int rowsThisPage = 0;
                Object[] lastCur = cursor;
                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    int p = 1;
                    if (hasCursor) {
                        for (Object cv : cursor) readPs.setObject(p++, cv);
                    }
                    readPs.setInt(p, batchSize);
                    readPs.setFetchSize(batchSize); // 流式读取，控制客户端内存峰值

                    try (ResultSet rs = readPs.executeQuery()) {
                        int rowsWritten = 0;
                        while (rs.next()) {
                            Object[] raw = new Object[srcColumns.size()];
                            for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                            Object[] curThis = new Object[pkSourceIdx.length];
                            for (int k = 0; k < pkSourceIdx.length; k++) curThis[k] = raw[pkSourceIdx[k]];
                            lastCur = curThis;          // 游标按原始主键推进（即使跳行也推进）
                            rowsThisPage++;
                            Object[] row = rowProc.process(srcColumns, raw);
                            if (row == null) continue;  // SKIP_ROW：计读不计写
                            bw.add(buildWriteRow(row, srcColumns.size(), writeColumns.size(), markTs));
                            rowsWritten++;
                        }
                        bw.flush(); // 内部：批成功commit+writeCount；批失败回退逐行+阈值容错
                        if (ctx.getRateLimiter() != null && rowsWritten > 0) ctx.getRateLimiter().acquire(rowsWritten);
                    }
                }

                tableRead += rowsThisPage;
                ctx.getReadCount().addAndGet(rowsThisPage);

                if (rowsThisPage > 0) {
                    cursor = lastCur;
                    hasCursor = true;
                    // chunk 保存：游标存为字符串数组（parse 回来 setObject 绑字符串，MySQL 隐式转型）
                    // 二进制主键(byte[])经 String.valueOf 会得到对象地址串导致续传错位，检测到则跳过保存(降级为不续传)并 WARN 一次
                    if (!mysqlSource) {
                        if (!nonMysqlWarned) {
                            ctx.log("WARN", "非 MySQL 源不支持字符串游标 chunk 续传,本表不保存断点: " + tc.getSourceTable());
                            nonMysqlWarned = true;
                        }
                    } else if (containsBinary(cursor)) {
                        if (!binaryWarned) {
                            ctx.log("WARN", "主键含二进制类型,不支持 chunk 续传,本表不保存断点: " + tc.getSourceTable());
                            binaryWarned = true;
                        }
                    } else {
                        ctx.getChunkSave().accept(tc.getSourceTable(),
                                com.alibaba.fastjson2.JSON.toJSONString(toStringArray(cursor)));
                    }
                    ctx.log("INFO", tc.getSourceTable() + " 已读 " + tableRead + " 行");
                }
                if (rowsThisPage < batchSize) {
                    break; // 最后一页：整表读完
                }
            }
        }
        // 整表正常读完才清除断点；被 stop 中断则保留游标供下次续传
        if (!ctx.getStopped().get()) {
            ctx.getChunkClear().accept(tc.getSourceTable());
        }
        return tableRead;
    }

    /** 无主键降级流式 INSERT（不分页/不去重/不支持续传）。返回本表读取行数。 */
    private long syncByStreaming(SyncContext ctx, TableSyncConfig tc, FieldMappingResolver.Resolved fm,
                                 Connection srcConn, Connection tgtConn,
                                 List<String> writeColumns, boolean markTs, String extraWhere) throws Exception {
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();
        List<String> srcColumns = fm.srcColumns;
        com.datanote.domain.integration.util.RowValueProcessor rowProc =
                new com.datanote.domain.integration.util.RowValueProcessor(fm.srcToFieldMapping);

        ctx.log("WARN", "表无主键，降级流式 INSERT(不分页/不去重/不支持续传): " + tc.getSourceTable());

        // 无主键强制 INSERT（UPSERT 无意义且无主键无法去重）
        String writeSql = WriteSqlBuilder.build(target.getDatabaseType(), "INSERT", tgtDb,
                tc.getTargetTable(), writeColumns, java.util.Collections.emptyList());
        String pageSql = ctx.getSource().scanSql(srcDb, tc.getSourceTable(), srcColumns, extraWhere);

        long tableRead = 0;
        int batchSize = SyncEngine.adaptiveBatch(ctx, srcDb, tc.getSourceTable(), srcConn);   // 大表自适应写批, 减提交次数
        try (PreparedStatement writePs = tgtConn.prepareStatement(writeSql);
             PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
            readPs.setFetchSize(Integer.MIN_VALUE); // MySQL 流式：逐行游标，避免整表载入内存
            BatchWriter bw = new BatchWriter(writePs, tgtConn, ctx, writeColumns, tc.getSourceTable(), tc.getTargetTable());
            try (ResultSet rs = readPs.executeQuery()) {
                int inBatch = 0;
                while (rs.next()) {
                    if (ctx.getStopped().get()) break;
                    Object[] raw = new Object[srcColumns.size()];
                    for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                    tableRead++;
                    ctx.getReadCount().incrementAndGet();
                    Object[] row = rowProc.process(srcColumns, raw);
                    if (row == null) continue; // SKIP_ROW：计读不计写
                    bw.add(buildWriteRow(row, srcColumns.size(), writeColumns.size(), markTs));
                    inBatch++;
                    if (inBatch >= batchSize) {
                        bw.flush();
                        if (ctx.getRateLimiter() != null) ctx.getRateLimiter().acquire(inBatch);
                        inBatch = 0;
                    }
                }
                bw.flush(); // 尾批
                if (ctx.getRateLimiter() != null && inBatch > 0) ctx.getRateLimiter().acquire(inBatch);
            }
        }
        return tableRead;
    }

    /** 由源行构造写入行：拷贝源列值，markTs 时在末尾追加当前时间戳列。 */
    private static Object[] buildWriteRow(Object[] row, int srcColCount, int writeColCount, boolean markTs) {
        Object[] writeRow = new Object[writeColCount];
        System.arraycopy(row, 0, writeRow, 0, srcColCount);
        if (markTs) writeRow[srcColCount] = new java.sql.Timestamp(System.currentTimeMillis());
        return writeRow;
    }

    /** 游标值转字符串数组用于 chunk 序列化：null 保持 null，其余 String.valueOf。 */
    private static Object[] toStringArray(Object[] cursor) {
        Object[] out = new Object[cursor.length];
        for (int i = 0; i < cursor.length; i++) out[i] = cursor[i] == null ? null : String.valueOf(cursor[i]);
        return out;
    }

    /** 游标是否含二进制主键值(byte[])：含则不支持 chunk 续传。 */
    private static boolean containsBinary(Object[] cursor) {
        if (cursor == null) return false;
        for (Object o : cursor) if (o instanceof byte[]) return true;
        return false;
    }
}
