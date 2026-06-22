-- 95_ai_auto_approve.sql —— dn_ai_session 增列 auto_approve(本任务批量自动批准写操作)
-- 现有库迁移: MySQL 不支持 ADD COLUMN IF NOT EXISTS, 重复执行会报 Duplicate column(可忽略)。
ALTER TABLE dn_ai_session
    ADD COLUMN auto_approve TINYINT NOT NULL DEFAULT 0
    COMMENT '本任务批量自动批准写操作(1=后续写操作免逐个审批; done时清0)' AFTER plan_json;
