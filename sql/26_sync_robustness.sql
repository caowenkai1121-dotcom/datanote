-- M2a 健壮性：dn_sync_job 加错误阈值/重试退避/限速；dn_task_execution 加 attempt
-- MySQL8 幂等：information_schema 判断列是否存在
USE datanote;

-- 通用宏不可用，逐列 PREPARE。dn_sync_job 6 列：
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='error_limit_rows');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN error_limit_rows INT NULL COMMENT ''脏数据条数上限,null不限''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='error_limit_ratio');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN error_limit_ratio DECIMAL(5,4) NULL COMMENT ''脏数据比率上限''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='retry_backoff_type');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN retry_backoff_type VARCHAR(20) DEFAULT ''FIXED_DELAY'' COMMENT ''FIXED_DELAY/EXPONENTIAL''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='retry_backoff_delay');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN retry_backoff_delay INT DEFAULT 5 COMMENT ''退避基数秒''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='rate_limit_mode');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN rate_limit_mode VARCHAR(10) DEFAULT ''NONE'' COMMENT ''NONE/ROWS/BATCHES''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='rate_limit_value');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN rate_limit_value INT NULL COMMENT ''令牌/秒''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- dn_task_execution 1 列：
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_task_execution' AND COLUMN_NAME='attempt');
SET @s:=IF(@c=0,'ALTER TABLE dn_task_execution ADD COLUMN attempt INT DEFAULT 1 COMMENT ''第几次重试''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
