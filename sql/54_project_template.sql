-- 项目管理 V2 PM2-M7：项目模板管理（幂等）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_project_template (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_name VARCHAR(128) NOT NULL,
  template_type VARCHAR(20) DEFAULT 'GENERAL',
  description   VARCHAR(512) DEFAULT NULL,
  config_json   MEDIUMTEXT COMMENT '模板配置快照(类型/env/标签/成员角色等)',
  created_by    VARCHAR(64) DEFAULT NULL,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_name (template_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目模板';
