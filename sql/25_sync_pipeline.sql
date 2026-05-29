-- M1 数据加工管道：dn_sync_job 加任务级前后置 SQL
USE datanote;
ALTER TABLE dn_sync_job
    ADD COLUMN IF NOT EXISTS pre_sql  LONGTEXT NULL COMMENT '任务级前置SQL(写入前执行,多语句分号分隔)',
    ADD COLUMN IF NOT EXISTS post_sql LONGTEXT NULL COMMENT '任务级后置SQL(写入后执行)';
