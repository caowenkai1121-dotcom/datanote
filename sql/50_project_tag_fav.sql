-- 项目管理 V2 PM2-M1：标签体系 + 收藏/置顶/最近访问（幂等）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_project_tag (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tag_name   VARCHAR(64) NOT NULL,
  tag_color  VARCHAR(16) DEFAULT '#1677ff',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tag_name (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目标签';

CREATE TABLE IF NOT EXISTS dn_project_tag_mapping (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  tag_id     BIGINT NOT NULL,
  UNIQUE KEY uk_proj_tag (project_id, tag_id),
  INDEX idx_ptm_project (project_id),
  INDEX idx_ptm_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目-标签关联';

CREATE TABLE IF NOT EXISTS dn_project_favorite (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  pinned     TINYINT DEFAULT 0 COMMENT '1=置顶',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fav_user_proj (username, project_id),
  INDEX idx_fav_user (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目收藏/置顶';

CREATE TABLE IF NOT EXISTS dn_project_access (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  access_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_acc_user_proj (username, project_id),
  INDEX idx_acc_user (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目最近访问';
