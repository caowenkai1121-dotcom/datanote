USE datanote;
CREATE TABLE IF NOT EXISTS dn_cdc_dead_letter (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    job_id        BIGINT       NOT NULL,
    source_db     VARCHAR(100) DEFAULT NULL,
    source_table  VARCHAR(128) DEFAULT NULL,
    origin_value  LONGTEXT     COMMENT 'JSON 原始变更(截断)',
    error_reason  TEXT         COMMENT '失败原因',
    error_type    VARCHAR(20)  COMMENT 'PARSE/APPLY',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_time (job_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC 死信(坏事件)';
