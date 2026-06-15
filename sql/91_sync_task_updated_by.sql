-- R120: 同步任务(dn_sync_task)并发编辑乐观版本校验 — 新增 updated_by 列(记最后修改人)
-- 注: MySQL 不支持 ADD COLUMN IF NOT EXISTS, 应用前请确认列不存在(运维脚本已判存幂等)
ALTER TABLE dn_sync_task ADD COLUMN updated_by VARCHAR(64) NULL COMMENT '最后修改人(并发冲突提示)' AFTER created_by;
