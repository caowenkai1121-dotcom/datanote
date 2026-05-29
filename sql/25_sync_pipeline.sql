-- M1 数据加工管道：dn_sync_job 加任务级前后置 SQL
-- MySQL 8 不支持 ALTER ... ADD COLUMN IF NOT EXISTS，用 information_schema 判断实现幂等
USE datanote;

SET @col_pre := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'datanote' AND TABLE_NAME = 'dn_sync_job' AND COLUMN_NAME = 'pre_sql');
SET @sql_pre := IF(@col_pre = 0,
    'ALTER TABLE dn_sync_job ADD COLUMN pre_sql LONGTEXT NULL COMMENT ''任务级前置SQL(写入前执行,多语句分号分隔)''',
    'SELECT 1');
PREPARE st FROM @sql_pre; EXECUTE st; DEALLOCATE PREPARE st;

SET @col_post := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'datanote' AND TABLE_NAME = 'dn_sync_job' AND COLUMN_NAME = 'post_sql');
SET @sql_post := IF(@col_post = 0,
    'ALTER TABLE dn_sync_job ADD COLUMN post_sql LONGTEXT NULL COMMENT ''任务级后置SQL(写入后执行)''',
    'SELECT 1');
PREPARE st FROM @sql_post; EXECUTE st; DEALLOCATE PREPARE st;
