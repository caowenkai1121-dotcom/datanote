-- 99_perf_indexes.sql — 大数据/高并发性能索引(幂等: 已存在则跳过, 可重复执行)
-- 对应性能审计: 调度 tick() 全表扫、消费排行/未消费聚合查询。CREATE INDEX 无 IF NOT EXISTS, 用 information_schema 守卫。

-- dn_scheduler_run: tick() 每15s WHERE status=WAITING + DISTINCT(run_date,run_type), 百万级历史会全表扫
SET @i1 := (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dn_scheduler_run' AND INDEX_NAME='idx_status_date_type');
SET @s1 := IF(@i1=0,
  'CREATE INDEX idx_status_date_type ON dn_scheduler_run(status, run_date, run_type)',
  'SELECT 1');
PREPARE p1 FROM @s1; EXECUTE p1; DEALLOCATE PREPARE p1;

-- dn_consumption_log: 消费排行/未消费预警 WHERE target_type IN(...) GROUP BY target_code + created_at 区间
SET @i2 := (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dn_consumption_log' AND INDEX_NAME='idx_cl_type_code_time');
SET @s2 := IF(@i2=0,
  'CREATE INDEX idx_cl_type_code_time ON dn_consumption_log(target_type, target_code, created_at)',
  'SELECT 1');
PREPARE p2 FROM @s2; EXECUTE p2; DEALLOCATE PREPARE p2;
