-- ============================================================
-- 76_project_p2.sql  项目管理P2
-- B7: 工单 owner(VARCHAR 80) 转任务灌 assignee(VARCHAR 64) 会截断/报错, 对齐为 80
-- ============================================================

ALTER TABLE dn_project_task MODIFY COLUMN assignee VARCHAR(80) DEFAULT NULL COMMENT '指派人';
