-- 项目管理 V2 PM2-M5：项目文档 Wiki（幂等）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_project_wiki_page (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  parent_id   BIGINT DEFAULT 0 COMMENT '父页面(0=根)',
  title       VARCHAR(256) NOT NULL,
  content     MEDIUMTEXT COMMENT 'Markdown 内容',
  sort_order  INT DEFAULT 0,
  created_by  VARCHAR(64) DEFAULT NULL,
  updated_by  VARCHAR(64) DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_wiki_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目文档Wiki';
