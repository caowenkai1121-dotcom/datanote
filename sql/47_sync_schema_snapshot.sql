-- 数据同步 DS-M7：源表 schema 快照（漂移分级——源vs源历史对比，可靠）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_sync_schema_snapshot (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id       BIGINT NOT NULL,
  source_table VARCHAR(256) NOT NULL,
  columns_json MEDIUMTEXT COMMENT '列名->类型 JSON(末次接受的源schema)',
  pk_json      VARCHAR(1024) COMMENT '主键列 JSON 数组',
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_job_table (job_id, source_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步源表schema快照';
