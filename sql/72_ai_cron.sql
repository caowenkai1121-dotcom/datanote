-- AI agent 定时自治任务(Wave5: cron 定时自治)
CREATE TABLE IF NOT EXISTS dn_ai_cron_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL COMMENT '任务名',
  schedule_cron VARCHAR(120) NOT NULL COMMENT 'Spring 6段cron 或 everyNm/everyNh',
  prompt TEXT NOT NULL COMMENT 'agent 任务提示',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  silent TINYINT NOT NULL DEFAULT 0 COMMENT '1=无重要变化不投递',
  owner VARCHAR(100) DEFAULT NULL COMMENT '归属用户',
  last_session_id VARCHAR(64) DEFAULT NULL COMMENT '最近一次运行的会话id',
  next_run DATETIME DEFAULT NULL COMMENT '下次执行时间(执行前先推进, 崩溃幂等)',
  last_run DATETIME DEFAULT NULL,
  last_status VARCHAR(60) DEFAULT NULL COMMENT 'done/blocked/wait_approval/error:...',
  run_count INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT NULL,
  updated_at DATETIME DEFAULT NULL,
  KEY idx_enabled_next (enabled, next_run)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI agent 定时自治任务';
