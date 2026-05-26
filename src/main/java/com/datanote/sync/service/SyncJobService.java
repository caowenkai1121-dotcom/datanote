package com.datanote.sync.service;

import com.alibaba.fastjson.JSON;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.dto.TableSyncConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 关系库同步任务管理：CRUD + 连接器构建。
 */
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final DnSyncJobMapper syncJobMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final ConnectionManager connectionManager;

    public List<DnSyncJob> list() {
        return syncJobMapper.selectList(null);
    }

    public DnSyncJob getById(Long id) {
        return syncJobMapper.selectById(id);
    }

    public DnSyncJob save(DnSyncJob job) {
        if (job.getId() != null) {
            job.setUpdatedAt(LocalDateTime.now());
            syncJobMapper.updateById(job);
        } else {
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            if (job.getStatus() == null) {
                job.setStatus("CREATED");
            }
            syncJobMapper.insert(job);
        }
        return job;
    }

    public void delete(Long id) {
        syncJobMapper.deleteById(id);
    }

    /** 把内存中的表配置（含更新后的增量断点）序列化写回 dn_sync_job.tableConfig。 */
    public void updateTableConfig(Long jobId, List<TableSyncConfig> tables) {
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setTableConfig(JSON.toJSONString(tables, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    /** 解析 table_config JSON。 */
    public List<TableSyncConfig> parseTables(DnSyncJob job) {
        if (job.getTableConfig() == null || job.getTableConfig().trim().isEmpty()) {
            return new ArrayList<>();
        }
        return JSON.parseArray(job.getTableConfig(), TableSyncConfig.class);
    }

    /** 为某数据源构建连接器（databaseType 取自 dn_datasource.type，归一为大写）。 */
    public DbConnector buildConnector(Long datasourceId, String db) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String type = ds.getType() == null ? "MYSQL" : ds.getType().toUpperCase();
        return new MysqlConnector(connectionManager, datasourceId, db, type);
    }
}
