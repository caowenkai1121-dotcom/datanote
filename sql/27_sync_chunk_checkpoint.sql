USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_chunk_checkpoint (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    sync_job_id   BIGINT       NOT NULL,
    source_table  VARCHAR(128) NOT NULL,
    cursor_value  LONGTEXT     COMMENT 'JSON 数组:复合主键游标值(字符串)',
    row_count     BIGINT       DEFAULT 0,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_table (sync_job_id, source_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量分片断点续传';
