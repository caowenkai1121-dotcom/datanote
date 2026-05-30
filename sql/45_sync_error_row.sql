-- 数据同步 DS-M1：脏数据 DLQ 错误行表（全量/增量坏行落表，可见可重试）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_sync_error_row (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id        BIGINT NOT NULL COMMENT '同步任务ID',
  run_id        BIGINT DEFAULT NULL COMMENT '执行ID(dn_task_execution.id)',
  source_table  VARCHAR(256) DEFAULT NULL COMMENT '源表',
  raw_row       MEDIUMTEXT COMMENT '写入行JSON(post-transform)',
  error_code    VARCHAR(64) DEFAULT NULL,
  error_msg     TEXT COMMENT '错误信息(截断)',
  stage         VARCHAR(20) DEFAULT NULL COMMENT 'FULL/INCREMENTAL',
  attempt       INT DEFAULT 1 COMMENT '所属重试次',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_job (job_id),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步坏行(DLQ)';
