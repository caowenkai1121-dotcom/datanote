package com.datanote.sync.service;

import com.alibaba.fastjson.JSON;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.FilterExpressionBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行数对账：每表两端 COUNT 比对（源叠加 filterExpression，目标不套过滤），写一条执行记录。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataReconciliationService {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;

    /** DS-M2：主键级 diff 单表主键集合上限，超过则放弃 PK diff（仅保留分桶结果），防 OOM。 */
    private static final int PK_DIFF_CAP = 2_000_000;
    private static final int PK_DIFF_SAMPLE = 100;

    public Map<String, Object> reconcile(Long jobId) throws Exception {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        DbConnector src = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector tgt = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean allMatch = true;
        for (TableSyncConfig tc : syncJobService.parseTables(job)) {
            String ew = FilterExpressionBuilder.build(tc.getFilterExpression());
            long sc = count(src, job.getSourceDb(), tc.getSourceTable(), ew);
            // 目标不套源过滤表达式（列名可能不同）
            long tcnt = count(tgt, job.getTargetDb(), tc.getTargetTable(), null);
            boolean match = sc == tcnt;
            allMatch &= match;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", tc.getSourceTable() + "->" + tc.getTargetTable());
            m.put("sourceCount", sc);
            m.put("targetCount", tcnt);
            m.put("match", match);
            rows.add(m);
        }
        writeExec(jobId, allMatch, rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", allMatch);
        result.put("tables", rows);
        return result;
    }

    private long count(DbConnector conn, String db, String table, String ew) throws Exception {
        String sql = MysqlConnector.buildCountSql(db, table, ew);
        try (Connection c = conn.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 分桶 checksum 深度对账:每表两端按主键分桶比对 count+xor,定位差异桶。 */
    public Map<String, Object> checksum(Long jobId) throws Exception {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在: " + jobId);
        int buckets = 16;
        DbConnector src = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector tgt = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        List<Map<String, Object>> tableResults = new ArrayList<>();
        boolean allMatch = true;
        for (com.datanote.sync.dto.TableSyncConfig tc : syncJobService.parseTables(job)) {
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("table", tc.getSourceTable() + "->" + tc.getTargetTable());
            try {
                com.datanote.sync.connector.TableMeta meta = src.getTableMeta(job.getSourceDb(), tc.getSourceTable());
                com.datanote.sync.util.FieldMappingResolver.Resolved fm =
                    com.datanote.sync.util.FieldMappingResolver.resolve(tc, meta.getColumns(), meta.getPrimaryKeys());
                if (fm.pkSourceColumns.isEmpty()) {
                    tr.put("match", false);
                    tr.put("error", "无主键,不支持checksum");
                    allMatch = false;
                    tableResults.add(tr);
                    continue;
                }
                ScanResult srcScan = runChecksum(src, job.getSourceDb(), tc.getSourceTable(), fm.srcColumns, fm.pkSourceColumns, buckets);
                ScanResult tgtScan = runChecksum(tgt, job.getTargetDb(), tc.getTargetTable(), fm.tgtColumns, fm.pkTargetColumns, buckets);
                Map<Integer, long[]> srcMap = srcScan.buckets;
                Map<Integer, long[]> tgtMap = tgtScan.buckets;
                List<Map<String, Object>> mism = new ArrayList<>();
                // 跨方言(如 MySQL→Doris)值序列化不同(datetime精度/decimal末尾零/float表示),
                // MD5 内容校验不可靠;仅当源目标同方言族才比对内容校验和,否则只比对分桶行数。
                boolean contentComparable = src.getDatabaseType().equalsIgnoreCase(tgt.getDatabaseType());
                java.util.Set<Integer> allKeys = new java.util.TreeSet<>();
                allKeys.addAll(srcMap.keySet());
                allKeys.addAll(tgtMap.keySet());
                for (Integer b : allKeys) {
                    long[] s = srcMap.getOrDefault(b, new long[]{0, 0, 0});
                    long[] t = tgtMap.getOrDefault(b, new long[]{0, 0, 0});
                    boolean diff = contentComparable
                            ? (s[0] != t[0] || s[1] != t[1] || s[2] != t[2])
                            : (s[0] != t[0]);
                    if (diff) {
                        Map<String, Object> mb = new LinkedHashMap<>();
                        mb.put("bucket", b);
                        mb.put("sourceCnt", s[0]);
                        mb.put("targetCnt", t[0]);
                        mb.put("sourceChk", Long.toHexString(s[1]) + Long.toHexString(s[2]));
                        mb.put("targetChk", Long.toHexString(t[1]) + Long.toHexString(t[2]));
                        mism.add(mb);
                    }
                }
                boolean match = mism.isEmpty();
                // DS-M2:同遍收集的主键集合做差 -> 缺失/多余清单(主键存在性与方言无关,跨方言安全)
                if (srcScan.pkCapped || tgtScan.pkCapped) {
                    tr.put("pkDiffNote", "表过大(>" + PK_DIFF_CAP + "行),跳过主键级diff,仅分桶比对");
                } else {
                    PkDiff pd = computePkDiff(srcScan.pks, tgtScan.pks, PK_DIFF_SAMPLE);
                    tr.put("missingInTargetCount", pd.missingCount);
                    tr.put("extraInTargetCount", pd.extraCount);
                    tr.put("missingSample", pd.missingSample);
                    tr.put("extraSample", pd.extraSample);
                    match = match && pd.missingCount == 0 && pd.extraCount == 0;
                }
                allMatch &= match;
                tr.put("bucketCount", buckets);
                tr.put("match", match);
                tr.put("mismatchBuckets", mism);
                if (!contentComparable) {
                    tr.put("note", "跨方言(" + src.getDatabaseType() + "→" + tgt.getDatabaseType()
                            + "):值序列化差异,仅比对分桶行数,内容校验和不可靠");
                }
            } catch (Exception e) {
                tr.put("match", false);
                tr.put("error", e.getMessage());
                allMatch = false;
            }
            tableResults.add(tr);
        }
        writeChecksumExec(jobId, allMatch, tableResults);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", allMatch);
        r.put("tables", tableResults);
        return r;
    }

    private ScanResult runChecksum(DbConnector conn, String db, String table,
                                   List<String> cols, List<String> pks, int buckets) throws Exception {
        String sql = com.datanote.sync.util.ChecksumSqlBuilder.buildRowHashSql(db, table, cols, pks);
        ScanResult r = new ScanResult();
        try (Connection c = conn.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            // 流式读取:逐行拉取避免大表 OOM(MySQL/Doris 均接受普通 fetchSize)
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                int pkCount = pks.size();
                while (rs.next()) {
                    StringBuilder pkKey = new StringBuilder();
                    for (int i = 1; i <= pkCount; i++) {
                        Object v = rs.getObject(i);
                        pkKey.append(v == null ? "\0" : String.valueOf(v));
                        if (i < pkCount) pkKey.append('#');
                    }
                    String key = pkKey.toString();
                    int bucket = Math.floorMod(key.hashCode(), buckets);
                    long[] hl = md5ToLongs(rs.getString("__h"));
                    long[] acc = r.buckets.get(bucket);
                    if (acc == null) {
                        acc = new long[]{0L, 0L, 0L};
                        r.buckets.put(bucket, acc);
                    }
                    acc[0]++;
                    acc[1] ^= hl[0];
                    acc[2] ^= hl[1];
                    // DS-M2:同遍收集主键集合(供主键级diff),超阈值则放弃收集省内存
                    if (!r.pkCapped) {
                        if (r.pks.size() >= PK_DIFF_CAP) { r.pkCapped = true; r.pks.clear(); }
                        else r.pks.add(key);
                    }
                }
            }
        }
        return r;
    }

    /** 单表单遍扫描结果:分桶聚合 + 主键集合(供主键级diff)。 */
    private static final class ScanResult {
        final Map<Integer, long[]> buckets = new LinkedHashMap<>();
        final java.util.Set<String> pks = new java.util.HashSet<>();
        boolean pkCapped = false;
    }

    /** 主键级差异:缺失(源有目标无)/多余(目标有源无) 计数 + 样本。 */
    static final class PkDiff {
        long missingCount = 0, extraCount = 0;
        final List<String> missingSample = new ArrayList<>();
        final List<String> extraSample = new ArrayList<>();
    }

    /** 纯函数:两端主键集合做差,输出缺失/多余计数与样本(各最多 sample 条)。 */
    static PkDiff computePkDiff(java.util.Set<String> src, java.util.Set<String> tgt, int sample) {
        PkDiff d = new PkDiff();
        for (String k : src) {
            if (!tgt.contains(k)) { d.missingCount++; if (d.missingSample.size() < sample) d.missingSample.add(k); }
        }
        for (String k : tgt) {
            if (!src.contains(k)) { d.extraCount++; if (d.extraSample.size() < sample) d.extraSample.add(k); }
        }
        return d;
    }

    /** 32位小写 hex 的 MD5 解析为前8字节/后8字节两个 long;null 或长度不足当全 0。 */
    private static long[] md5ToLongs(String hex) {
        if (hex == null || hex.length() < 32) return new long[]{0L, 0L};
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) hi = (hi << 8) | (Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) & 0xFF);
        for (int i = 8; i < 16; i++) lo = (lo << 8) | (Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) & 0xFF);
        return new long[]{hi, lo};
    }

    private void writeChecksumExec(Long jobId, boolean ok, List<Map<String, Object>> rows) {
        try {
            DnTaskExecution e = new DnTaskExecution();
            e.setSyncTaskId(jobId);
            e.setTaskType("DataChecksum");
            e.setTriggerType("manual");
            e.setStatus(ok ? "SUCCESS" : "FAILED");
            e.setStartTime(java.time.LocalDateTime.now());
            e.setEndTime(java.time.LocalDateTime.now());
            e.setCreatedAt(java.time.LocalDateTime.now());
            e.setLog(JSON.toJSONString(rows));
            taskExecutionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("checksum执行记录失败 jobId={}", jobId, ex);
        }
    }

    private void writeExec(Long jobId, boolean ok, List<Map<String, Object>> rows) {
        try {
            DnTaskExecution e = new DnTaskExecution();
            e.setSyncTaskId(jobId);
            e.setTaskType("DataReconciliation");
            e.setTriggerType("manual");
            e.setStatus(ok ? "SUCCESS" : "FAILED");
            e.setStartTime(LocalDateTime.now());
            e.setEndTime(LocalDateTime.now());
            e.setCreatedAt(LocalDateTime.now());
            e.setLog(JSON.toJSONString(rows));
            taskExecutionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("对账执行记录失败 jobId={}", jobId, ex);
        }
    }
}
