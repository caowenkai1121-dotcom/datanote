package com.datanote.domain.integration.service;

import com.alibaba.fastjson.JSON;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.integration.mapper.DnSyncChunkCheckpointMapper;
import com.datanote.domain.integration.mapper.DnSyncJobDependencyMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.model.DnSyncChunkCheckpoint;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.model.DnSyncJobDependency;
import com.datanote.domain.integration.connector.ConnectionManager;
import com.datanote.domain.integration.connector.DbConnector;
import com.datanote.domain.integration.connector.MysqlConnector;
import com.datanote.domain.integration.connector.TableMeta;
import com.datanote.domain.integration.dto.TableSyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系库同步任务管理：CRUD + 连接器构建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final DnSyncJobMapper syncJobMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final ConnectionManager connectionManager;
    private final DnSyncChunkCheckpointMapper chunkCheckpointMapper;
    private final AuditLogService auditLogService;
    private final DnSyncJobDependencyMapper dependencyMapper;

    /**
     * 任务列表：列表页不需要 tableConfig/fieldMapping 两个 LONGTEXT，查出后置 null 以减少
     * 传输与前端解析开销（完整内容由 getById 详情接口返回）。
     */
    public List<DnSyncJob> list() {
        long start = System.currentTimeMillis();
        List<DnSyncJob> jobs = syncJobMapper.selectList(null);
        if (jobs == null) {
            return new ArrayList<>();
        }
        for (DnSyncJob job : jobs) {
            if (job == null) {
                continue;
            }
            job.setTableConfig(null);
            job.setFieldMapping(null);
        }
        log.info("sync-job list 返回 {} 条，耗时 {}ms", jobs.size(), System.currentTimeMillis() - start);
        return jobs;
    }

    public DnSyncJob getById(Long id) {
        if (id == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        return syncJobMapper.selectById(id);
    }

    private static final Set<String> SYNC_MODES = new HashSet<>(Arrays.asList("FULL", "INCREMENTAL", "CDC"));
    private static final Set<String> WRITE_MODES = new HashSet<>(Arrays.asList("UPSERT", "INSERT", "INSERT_IGNORE"));

    /** 保存前服务端校验：必填项 / 同步模式 / 写入模式 / cron / tableConfig JSON / 增量字段。非法抛 IllegalArgumentException。 */
    void validate(DnSyncJob job) {
        if (job == null) {
            throw new IllegalArgumentException("任务对象不能为空");
        }
        if (isBlank(job.getJobName())) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        if (job.getSourceDsId() == null || job.getTargetDsId() == null) {
            throw new IllegalArgumentException("源/目标数据源不能为空");
        }
        String mode = job.getSyncMode() == null ? "" : job.getSyncMode().toUpperCase();
        if (!SYNC_MODES.contains(mode)) {
            throw new IllegalArgumentException("非法同步模式: " + job.getSyncMode());
        }
        if (job.getWriteMode() != null && !job.getWriteMode().trim().isEmpty()
                && !WRITE_MODES.contains(job.getWriteMode().toUpperCase())) {
            throw new IllegalArgumentException("非法写入模式: " + job.getWriteMode());
        }
        if (!isBlank(job.getScheduleCron())) {
            try {
                CronExpression.parse(job.getScheduleCron().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("非法 cron 表达式: " + job.getScheduleCron());
            }
        }
        List<TableSyncConfig> tables;
        if (!isBlank(job.getTableConfig())) {
            try {
                tables = JSON.parseArray(job.getTableConfig(), TableSyncConfig.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("table_config 不是合法 JSON 数组");
            }
            if (tables == null) {
                throw new IllegalArgumentException("table_config 不是合法 JSON 数组");
            }
        } else {
            tables = new ArrayList<>();
        }
        for (TableSyncConfig t : tables) {
            if (t == null) {
                throw new IllegalArgumentException("table_config 含空表配置项");
            }
            if (isBlank(t.getSourceTable()) || isBlank(t.getTargetTable())) {
                throw new IllegalArgumentException("表配置缺少 sourceTable/targetTable");
            }
            if ("INCREMENTAL".equals(mode) && isBlank(t.getIncrementalField())) {
                throw new IllegalArgumentException("增量模式下表 " + t.getSourceTable() + " 缺少 incrementalField");
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public DnSyncJob save(DnSyncJob job) {
        validate(job);
        if (job.getId() != null) {
            DnSyncJob old = syncJobMapper.selectById(job.getId());
            job.setUpdatedAt(LocalDateTime.now());
            syncJobMapper.updateById(job);
            auditLogService.record(job.getId(), job.getJobName(), "UPDATE",
                    "before=" + summary(old) + " after=" + summary(job));
        } else {
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            if (job.getStatus() == null) {
                job.setStatus("CREATED");
            }
            syncJobMapper.insert(job);
            auditLogService.record(job.getId(), job.getJobName(), "CREATE", summary(job));
        }
        return job;
    }

    /** 审计摘要：关键字段 JSON（tableConfig 截断，避免过长）。 */
    private static String summary(DnSyncJob job) {
        if (job == null) {
            return "null";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("syncMode", job.getSyncMode());
        m.put("writeMode", job.getWriteMode());
        String tc = job.getTableConfig();
        m.put("tableConfig", tc == null ? null : (tc.length() > 500 ? tc.substring(0, 500) + "..." : tc));
        return JSON.toJSONString(m);
    }

    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        syncJobMapper.deleteById(id);
    }

    /** 仅更新任务状态（RUNNING/SUCCESS/FAILED 等），不动其他字段。 */
    public void updateStatus(Long jobId, String status) {
        if (jobId == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        if (isBlank(status)) {
            throw new BusinessException("任务状态不能为空");
        }
        DnSyncJob update = new DnSyncJob();
        update.setId(jobId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(update);
    }

    /** 把内存中的表配置（含更新后的增量断点）序列化写回 dn_sync_job.tableConfig。 */
    public void updateTableConfig(Long jobId, List<TableSyncConfig> tables) {
        if (jobId == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            log.warn("updateTableConfig 任务不存在, 跳过: jobId={}", jobId);
            return;
        }
        List<TableSyncConfig> safe = tables == null ? new ArrayList<>() : tables;
        job.setTableConfig(JSON.toJSONString(safe, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    /** 按表回写单张表的增量断点（读现有 JSON，仅改对应表，避免整段覆盖丢其他表）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public synchronized void updateTableCheckpoint(Long jobId, TableSyncConfig updated) {
        if (jobId == null || updated == null) {
            log.warn("updateTableCheckpoint 入参为空, 跳过: jobId={}, updated={}", jobId, updated);
            return;
        }
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            log.warn("updateTableCheckpoint 任务不存在, 跳过: jobId={}", jobId);
            return;
        }
        List<TableSyncConfig> tables = parseTables(job);
        boolean changed = false;
        for (TableSyncConfig t : tables) {
            if (t == null) {
                continue;
            }
            if (eq(t.getSourceTable(), updated.getSourceTable()) && eq(t.getTargetTable(), updated.getTargetTable())) {
                t.setIncrementalValue(updated.getIncrementalValue());
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        job.setTableConfig(JSON.toJSONString(tables, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    /** M2b：载入某表全量 chunk 游标 JSON(无则 null)。 */
    public String loadChunkCursor(Long jobId, String sourceTable) {
        if (jobId == null || isBlank(sourceTable)) {
            return null;
        }
        DnSyncChunkCheckpoint cp = chunkCheckpointMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncChunkCheckpoint>()
                .eq(DnSyncChunkCheckpoint::getSyncJobId, jobId)
                .eq(DnSyncChunkCheckpoint::getSourceTable, sourceTable));
        return cp == null ? null : cp.getCursorValue();
    }

    /** M2b：保存/更新 chunk 游标。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public synchronized void saveChunkCursor(Long jobId, String sourceTable, String cursorJson) {
        if (jobId == null || isBlank(sourceTable)) {
            log.warn("saveChunkCursor 入参为空, 跳过: jobId={}, sourceTable={}", jobId, sourceTable);
            return;
        }
        DnSyncChunkCheckpoint cp = chunkCheckpointMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncChunkCheckpoint>()
                .eq(DnSyncChunkCheckpoint::getSyncJobId, jobId)
                .eq(DnSyncChunkCheckpoint::getSourceTable, sourceTable));
        if (cp == null) {
            cp = new DnSyncChunkCheckpoint();
            cp.setSyncJobId(jobId); cp.setSourceTable(sourceTable); cp.setCursorValue(cursorJson);
            chunkCheckpointMapper.insert(cp);
        } else {
            cp.setCursorValue(cursorJson);
            chunkCheckpointMapper.updateById(cp);
        }
    }

    /** M2b：清除某表 chunk 断点。 */
    public void clearChunkCursor(Long jobId, String sourceTable) {
        if (jobId == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        if (isBlank(sourceTable)) {
            throw new BusinessException("源表名不能为空");
        }
        chunkCheckpointMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncChunkCheckpoint>()
                .eq(DnSyncChunkCheckpoint::getSyncJobId, jobId)
                .eq(DnSyncChunkCheckpoint::getSourceTable, sourceTable));
    }

    /** M3c：查看任务断点——增量每表水位 + 全量 chunk 游标。 */
    public Map<String, Object> getCheckpoints(Long jobId) {
        if (jobId == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        DnSyncJob job = getById(jobId);
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> incr = new ArrayList<>();
        if (job != null) {
            for (TableSyncConfig tc : parseTables(job)) {
                if (tc == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", tc.getSourceTable());
                m.put("incrementalField", tc.getIncrementalField());
                m.put("incrementalValue", tc.getIncrementalValue());
                incr.add(m);
            }
        }
        r.put("incremental", incr);
        r.put("chunk", chunkCheckpointMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncChunkCheckpoint>()
                .eq(DnSyncChunkCheckpoint::getSyncJobId, jobId)));
        return r;
    }

    /** M3c：重置某表增量水位（置空，下次从初值重扫）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void resetIncremental(Long jobId, String sourceTable) {
        if (jobId == null) {
            throw new BusinessException("任务 id 不能为空");
        }
        if (isBlank(sourceTable)) {
            throw new BusinessException("源表名不能为空");
        }
        DnSyncJob job = getById(jobId);
        if (job == null) {
            log.warn("resetIncremental 任务不存在, 跳过: jobId={}", jobId);
            return;
        }
        List<TableSyncConfig> tables = parseTables(job);
        boolean matched = false;
        for (TableSyncConfig tc : tables) {
            if (tc != null && eq(tc.getSourceTable(), sourceTable)) {
                tc.setIncrementalValue(null);
                matched = true;
                break; // 仅重置首个匹配表,防 malformed 配置(同名)误重置多表
            }
        }
        if (!matched) {
            log.warn("resetIncremental 未找到匹配表, 跳过回写: jobId={}, sourceTable={}", jobId, sourceTable);
            return;
        }
        updateTableConfig(jobId, tables);
    }

    /**
     * 同步前预检：源/目标库连通性、源表主键(是否单列、能否自动建表)、目标库可达、cron 合法性。
     * 返回 {ok, checks:[{name, ok, message}]}，逐项捕获异常不抛出。
     */
    public Map<String, Object> precheck(DnSyncJob job) {
        if (job == null) {
            throw new BusinessException("任务对象不能为空");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean allOk = true;

        if (!isBlank(job.getScheduleCron())) {
            boolean ok = true;
            String msg = "cron 合法";
            try {
                CronExpression.parse(job.getScheduleCron().trim());
            } catch (Exception e) {
                ok = false;
                msg = "非法 cron: " + e.getMessage();
            }
            checks.add(check("cron", ok, msg));
            allOk &= ok;
        }

        try {
            DbConnector src = buildConnector(job.getSourceDsId(), job.getSourceDb());
            try (Connection c = src.getConnection()) {
                checks.add(check("source", true, "源库连接成功"));
            }
            String syncModeUpper = job.getSyncMode() == null ? "FULL" : job.getSyncMode().toUpperCase();
            for (TableSyncConfig t : parseTables(job)) {
                try {
                    TableMeta meta = src.getTableMeta(job.getSourceDb(), t.getSourceTable());
                    if (meta == null || meta.getColumns() == null || meta.getColumns().isEmpty()) {
                        checks.add(check("table:" + t.getSourceTable(), false, "源表不存在或无列"));
                        allOk = false;
                    } else {
                        int pk = meta.getPrimaryKeys() == null ? 0 : meta.getPrimaryKeys().size();
                        boolean pkOk;
                        String m;
                        if (pk == 1) {
                            pkOk = true;
                            m = "单列主键";
                        } else if (pk > 1) {
                            pkOk = true;
                            m = "复合主键(已支持 keyset)";
                        } else {
                            // pk == 0
                            boolean isFull = "FULL".equals(syncModeUpper) || "".equals(syncModeUpper);
                            if (isFull) {
                                pkOk = true;
                                m = "无主键(全量走流式 INSERT,不去重/不续传)";
                            } else {
                                pkOk = false;
                                m = "无主键:增量/CDC 需主键定位,不支持";
                            }
                        }
                        checks.add(check("table:" + t.getSourceTable(), pkOk, m));
                        allOk &= pkOk;
                    }
                } catch (Exception e) {
                    checks.add(check("table:" + t.getSourceTable(), false, "读取失败: " + e.getMessage()));
                    allOk = false;
                }
            }
            // CDC 源库额外检查：binlog 开启状态、格式、账号权限
            if ("CDC".equals(syncModeUpper)) {
                try (Connection conn = src.getConnection()) {
                    // 1. log_bin
                    try (java.sql.Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'log_bin'")) {
                        if (rs.next()) {
                            String val = rs.getString(2);
                            boolean ok = "ON".equalsIgnoreCase(val);
                            checks.add(check("binlog", ok, ok ? "binlog 已开启" : "源库未开启 binlog"));
                            allOk &= ok;
                        } else {
                            checks.add(check("binlog", false, "无法读取 log_bin 变量"));
                            allOk = false;
                        }
                    } catch (Exception e) {
                        checks.add(check("binlog", false, "检查失败: " + e.getMessage()));
                        allOk = false;
                    }
                    // 2. binlog_format
                    try (java.sql.Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'binlog_format'")) {
                        if (rs.next()) {
                            String val = rs.getString(2);
                            boolean ok = "ROW".equalsIgnoreCase(val);
                            checks.add(check("binlog_format", ok, ok ? "binlog_format=ROW" : "binlog_format 非 ROW(当前=" + val + ")"));
                            allOk &= ok;
                        } else {
                            checks.add(check("binlog_format", false, "无法读取 binlog_format 变量"));
                            allOk = false;
                        }
                    } catch (Exception e) {
                        checks.add(check("binlog_format", false, "检查失败: " + e.getMessage()));
                        allOk = false;
                    }
                    // 3. REPLICATION 权限
                    try (java.sql.Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery("SHOW GRANTS")) {
                        StringBuilder grants = new StringBuilder();
                        while (rs.next()) {
                            grants.append(rs.getString(1)).append(" ");
                        }
                        String g = grants.toString().toUpperCase();
                        boolean hasAll = g.contains("ALL PRIVILEGES");
                        boolean hasSlave = g.contains("REPLICATION SLAVE");
                        boolean hasClient = g.contains("REPLICATION CLIENT");
                        boolean ok = hasAll || (hasSlave && hasClient);
                        checks.add(check("cdc_grants", ok, ok ? "账号具备 REPLICATION 权限" : "账号缺少 REPLICATION SLAVE/CLIENT 权限"));
                        allOk &= ok;
                    } catch (Exception e) {
                        checks.add(check("cdc_grants", false, "检查失败: " + e.getMessage()));
                        allOk = false;
                    }
                } catch (Exception e) {
                    checks.add(check("cdc_check", false, "CDC 检查连接失败: " + e.getMessage()));
                    allOk = false;
                }
            }
        } catch (Exception e) {
            checks.add(check("source", false, "源库连接失败: " + e.getMessage()));
            allOk = false;
        }

        try {
            DbConnector tgt = buildConnector(job.getTargetDsId(), job.getTargetDb());
            tgt.listTables(job.getTargetDb());
            checks.add(check("target", true, "目标库连接成功(连通)"));
        } catch (Exception e) {
            checks.add(check("target", false, "目标库连接失败: " + e.getMessage()));
            allOk = false;
        }

        result.put("ok", allOk);
        result.put("checks", checks);
        return result;
    }

    private static Map<String, Object> check(String name, boolean ok, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("message", msg);
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 解析 table_config JSON。 */
    public List<TableSyncConfig> parseTables(DnSyncJob job) {
        if (job == null || isBlank(job.getTableConfig())) {
            return new ArrayList<>();
        }
        try {
            List<TableSyncConfig> tables = JSON.parseArray(job.getTableConfig(), TableSyncConfig.class);
            return tables == null ? new ArrayList<>() : tables;
        } catch (Exception e) {
            log.warn("parseTables 解析 table_config 失败, 返回空: jobId={}, err={}",
                    job.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 为某数据源构建连接器（databaseType 取自 dn_datasource.type，归一为大写）。 */
    public DbConnector buildConnector(Long datasourceId, String db) {
        if (datasourceId == null) {
            throw new IllegalArgumentException("数据源 id 不能为空");
        }
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String type = ds.getType() == null ? "MYSQL" : ds.getType().toUpperCase();
        // DS-M8：PostgreSQL 源走 PostgresConnector（db 即 PG schema）
        if ("POSTGRESQL".equals(type) || "POSTGRES".equals(type) || "PG".equals(type)) {
            return new com.datanote.domain.integration.connector.PostgresConnector(connectionManager, datasourceId, db);
        }
        // DS-M9：SQL Server 源走 SqlServerConnector（db 即 schema，默认 dbo）
        if ("SQLSERVER".equals(type) || "MSSQL".equals(type) || "SQL_SERVER".equals(type)) {
            return new com.datanote.domain.integration.connector.SqlServerConnector(connectionManager, datasourceId, db);
        }
        // DS-M9：Oracle 源走 OracleConnector（db 即 schema/owner，大写）
        if ("ORACLE".equals(type)) {
            return new com.datanote.domain.integration.connector.OracleConnector(connectionManager, datasourceId, db);
        }
        return new MysqlConnector(connectionManager, datasourceId, db, type);
    }

    // ===== M4c：任务依赖（轻量 DAG）CRUD + 防环 =====

    /** 列出某任务的上游依赖。 */
    public List<DnSyncJobDependency> listDependencies(Long jobId) {
        if (jobId == null) {
            return new ArrayList<>();
        }
        return dependencyMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncJobDependency>()
                        .eq(DnSyncJobDependency::getSyncJobId, jobId));
    }

    /**
     * 添加上游依赖：拒绝自依赖；防环——一次性加载全部依赖边构建邻接表(消除 DFS 内逐节点查库的 N+1)，
     * 从 upstreamId 沿其上游链在内存 DFS，若可达 jobId 则会成环，拒绝；否则插入（唯一键重复忽略）。
     */
    public void addDependency(Long jobId, Long upstreamId) {
        if (jobId == null || upstreamId == null) {
            throw new IllegalArgumentException("jobId/upstreamId 不能为空");
        }
        if (jobId.equals(upstreamId)) {
            throw new IllegalArgumentException("不能依赖自身");
        }
        Map<Long, List<Long>> adjacency = loadDependencyAdjacency();
        if (reachable(upstreamId, jobId, new HashSet<>(), adjacency)) {
            throw new IllegalArgumentException("会形成环依赖");
        }
        DnSyncJobDependency dep = new DnSyncJobDependency();
        dep.setSyncJobId(jobId);
        dep.setUpstreamSyncJobId(upstreamId);
        dep.setDependsAll(1);
        dep.setCreateTime(LocalDateTime.now());
        try {
            dependencyMapper.insert(dep);
        } catch (org.springframework.dao.DuplicateKeyException ignore) {
            // 唯一键重复忽略
        }
    }

    /** 一次性把全部依赖边读入内存邻接表：syncJobId -> 其直接上游 id 列表（防环 DFS 用，避免 N+1）。 */
    private Map<Long, List<Long>> loadDependencyAdjacency() {
        Map<Long, List<Long>> adjacency = new java.util.HashMap<>();
        List<DnSyncJobDependency> edges = dependencyMapper.selectList(null);
        if (edges == null) {
            return adjacency;
        }
        for (DnSyncJobDependency d : edges) {
            if (d == null || d.getSyncJobId() == null || d.getUpstreamSyncJobId() == null) {
                continue;
            }
            adjacency.computeIfAbsent(d.getSyncJobId(), k -> new ArrayList<>()).add(d.getUpstreamSyncJobId());
        }
        return adjacency;
    }

    /** 基于内存邻接表，从 from 沿上游链 DFS 是否可达 target。 */
    private boolean reachable(Long from, Long target, Set<Long> visited, Map<Long, List<Long>> adjacency) {
        if (from == null) {
            return false;
        }
        if (from.equals(target)) {
            return true;
        }
        if (!visited.add(from)) {
            return false;
        }
        List<Long> ups = adjacency.get(from);
        if (ups == null) {
            return false;
        }
        for (Long up : ups) {
            if (reachable(up, target, visited, adjacency)) {
                return true;
            }
        }
        return false;
    }

    /** 移除上游依赖。 */
    public void removeDependency(Long jobId, Long upstreamId) {
        if (jobId == null || upstreamId == null) {
            throw new BusinessException("jobId/upstreamId 不能为空");
        }
        dependencyMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnSyncJobDependency>()
                        .eq(DnSyncJobDependency::getSyncJobId, jobId)
                        .eq(DnSyncJobDependency::getUpstreamSyncJobId, upstreamId));
    }
}
