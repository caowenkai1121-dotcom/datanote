-- 项目管理 V2 PM2-M3：任务待办 + 里程碑（幂等）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_project_milestone (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512) DEFAULT NULL,
  start_date  DATE DEFAULT NULL,
  end_date    DATE DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ms_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目里程碑';

CREATE TABLE IF NOT EXISTS dn_project_task (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id   BIGINT NOT NULL,
  milestone_id BIGINT DEFAULT NULL,
  title        VARCHAR(256) NOT NULL,
  description  VARCHAR(1024) DEFAULT NULL,
  assignee     VARCHAR(64) DEFAULT NULL,
  priority     VARCHAR(8) DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
  status       VARCHAR(8) DEFAULT 'TODO' COMMENT 'TODO/DOING/DONE',
  due_date     DATE DEFAULT NULL,
  created_by   VARCHAR(64) DEFAULT NULL,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_task_project (project_id),
  INDEX idx_task_status (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目任务待办';
