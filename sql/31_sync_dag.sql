USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_job_dependency (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    sync_job_id          BIGINT NOT NULL COMMENT '下游任务',
    upstream_sync_job_id BIGINT NOT NULL COMMENT '上游任务',
    depends_all          TINYINT(1) DEFAULT 1 COMMENT '1=全部上游SUCCESS才触发',
    create_time          DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dep (sync_job_id, upstream_sync_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务依赖(轻量DAG)';
-- dn_sync_job.priority(幂等)
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='priority');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN priority INT DEFAULT 5 COMMENT ''调度优先级,大者先跑''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
