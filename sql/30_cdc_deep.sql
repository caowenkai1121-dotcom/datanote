-- M4b CDC 深水：dn_sync_job 加 CDC 增量快照/DDL 同步两开关（默认关，线上行为零变化）
-- MySQL8 幂等：information_schema 判断列是否存在 + PREPARE（模式同 sql/26）
USE datanote;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='incremental_snapshot_enabled');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN incremental_snapshot_enabled TINYINT DEFAULT 0 COMMENT ''CDC增量快照开关''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='ddl_sync_enabled');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN ddl_sync_enabled TINYINT DEFAULT 0 COMMENT ''CDC DDL同步开关''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- signal 表（Debezium DDD-3 增量快照要求）：须建在【源库】且被捕获；目标库不需要。
-- 本次不动源库，结构如下，由运维在源库执行：
-- CREATE TABLE dn_cdc_signal (id VARCHAR(64) PRIMARY KEY, type VARCHAR(32) NOT NULL, data VARCHAR(2048) NULL) ENGINE=InnoDB;
