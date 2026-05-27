-- 关系库同步功能建表（计划1只用 dn_sync_job，dn_cdc_* 为 CDC 计划预建）
USE datanote;

-- 关系库同步任务定义
CREATE TABLE IF NOT EXISTS dn_sync_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_name        VARCHAR(200) NOT NULL COMMENT '任务名称',
    source_ds_id    BIGINT       NOT NULL COMMENT '源数据源ID（dn_datasource）',
    target_ds_id    BIGINT       NOT NULL COMMENT '目标数据源ID（dn_datasource）',
    source_db       VARCHAR(100) DEFAULT NULL COMMENT '源库',
    target_db       VARCHAR(100) DEFAULT NULL COMMENT '目标库',
    sync_mode       VARCHAR(20)  NOT NULL DEFAULT 'FULL' COMMENT 'FULL/INCREMENTAL/CDC',
    table_config    LONGTEXT     COMMENT 'JSON: [{sourceTable,targetTable,createTargetTable,incrementalField,incrementalType,incrementalValue}]',
    field_mapping   LONGTEXT     COMMENT 'JSON 字段映射(可选)',
    write_mode      VARCHAR(20)  DEFAULT 'UPSERT' COMMENT 'UPSERT/INSERT/INSERT_IGNORE',
    batch_size      INT          DEFAULT 1000 COMMENT '批量大小',
    schedule_cron   VARCHAR(64)  DEFAULT NULL COMMENT 'Cron(全量/增量用)',
    schedule_status VARCHAR(16)  DEFAULT 'offline' COMMENT 'online/offline',
    status          VARCHAR(20)  DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/STOPPED/PAUSED/FAILED',
    retry_times     INT          DEFAULT 1 COMMENT '失败重试次数',
    timeout_seconds INT          DEFAULT 0 COMMENT '超时(秒)',
    created_by      VARCHAR(50)  DEFAULT '',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_sync_mode (sync_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系库同步任务';

-- CDC binlog 位点（CDC 计划使用）
CREATE TABLE IF NOT EXISTS dn_cdc_offset (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    job_id       BIGINT       NOT NULL COMMENT '关联 dn_sync_job.id',
    offset_key   VARCHAR(512) NOT NULL COMMENT 'Debezium offset key',
    offset_value LONGTEXT     COMMENT 'Debezium offset value',
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_key (job_id, offset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC binlog 位点';

-- CDC 表结构变更历史（CDC 计划使用）
CREATE TABLE IF NOT EXISTS dn_cdc_schema_history (
    id           BIGINT     NOT NULL AUTO_INCREMENT,
    job_id       BIGINT     NOT NULL COMMENT '关联 dn_sync_job.id',
    history_data LONGTEXT   COMMENT 'Debezium schema history 一条记录(JSON)',
    created_at   DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC 表结构变更历史';
