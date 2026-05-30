USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_job_audit (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    job_id         BIGINT       NOT NULL,
    job_name       VARCHAR(200) DEFAULT NULL,
    operation_type VARCHAR(20)  NOT NULL COMMENT 'CREATE/UPDATE/RUN/STOP/RESET/DELETE',
    operator       VARCHAR(64)  DEFAULT NULL,
    change_detail  LONGTEXT     COMMENT '变更/操作详情',
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_created (job_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务操作审计';
