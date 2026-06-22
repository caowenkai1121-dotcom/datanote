-- 96_ai_autonomous.sql —— dn_ai_session 增列: 无人值守自主执行(autonomous mode)
-- 现有库迁移: MySQL 不支持 ADD COLUMN IF NOT EXISTS, 重复执行报 Duplicate column(可忽略)。
ALTER TABLE dn_ai_session
    ADD COLUMN autonomous       TINYINT  NOT NULL DEFAULT 0 COMMENT '无人值守自主执行(1=后台驱动器持续推进至计划完成/预算耗尽)' AFTER auto_approve,
    ADD COLUMN auto_max_steps   INT      NOT NULL DEFAULT 0 COMMENT '自主任务全程生产步硬上限(0=不限, 防失控)' AFTER autonomous,
    ADD COLUMN autonomous_until DATETIME DEFAULT NULL COMMENT '自主任务墙钟截止(超时自动收尾)' AFTER auto_max_steps,
    ADD COLUMN last_heartbeat   DATETIME DEFAULT NULL COMMENT '自主驱动心跳(检测卡死/防多实例重复领取)' AFTER autonomous_until,
    ADD COLUMN auto_idle_count  INT      NOT NULL DEFAULT 0 COMMENT '自主连续无进展周期数(熔断用)' AFTER last_heartbeat;
