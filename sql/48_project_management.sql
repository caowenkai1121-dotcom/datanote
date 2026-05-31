-- 项目管理模块（PM）：项目 + 成员 + 资产关联 + 发布版本。全部 CREATE IF NOT EXISTS 幂等。
USE datanote;

-- 项目主表（软删除/归档）
CREATE TABLE IF NOT EXISTS dn_project (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_code  VARCHAR(64) NOT NULL COMMENT '项目编码(唯一)',
  project_name  VARCHAR(128) NOT NULL,
  description   VARCHAR(512) DEFAULT NULL,
  project_type  VARCHAR(20) DEFAULT 'GENERAL' COMMENT 'GENERAL/DATASYNC/DEVSQL/HYBRID',
  env           VARCHAR(10) DEFAULT 'DEV' COMMENT 'DEV/PROD/MIXED',
  owner         VARCHAR(64) DEFAULT NULL COMMENT '负责人(username)',
  sensitivity   VARCHAR(16) DEFAULT 'NORMAL' COMMENT 'NORMAL/SENSITIVE',
  tags          VARCHAR(256) DEFAULT NULL COMMENT '逗号分隔标签',
  status        VARCHAR(12) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/DELETED',
  created_by    VARCHAR(64) DEFAULT NULL,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  archived_at   DATETIME DEFAULT NULL,
  deleted_at    DATETIME DEFAULT NULL,
  UNIQUE KEY uk_project_code (project_code),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 项目成员（username + 项目角色）
CREATE TABLE IF NOT EXISTS dn_project_member (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id   BIGINT NOT NULL,
  username     VARCHAR(64) NOT NULL,
  project_role VARCHAR(16) NOT NULL DEFAULT 'VIEWER' COMMENT 'OWNER/ADMIN/DEVELOPER/OPS/VIEWER',
  added_by     VARCHAR(64) DEFAULT NULL,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_project_user (project_id, username),
  INDEX idx_pm_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员';

-- 项目-资产关联（多对多，不动现有资产表）
CREATE TABLE IF NOT EXISTS dn_project_asset (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  asset_type  VARCHAR(20) NOT NULL COMMENT 'SYNC_JOB/SCRIPT/DATASOURCE/QUALITY_RULE',
  asset_id    BIGINT NOT NULL,
  asset_name  VARCHAR(256) DEFAULT NULL COMMENT '冗余展示名',
  created_by  VARCHAR(64) DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_project_asset (project_id, asset_type, asset_id),
  INDEX idx_pa_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目资产关联';

-- 项目发布版本（轻量单级审批）
CREATE TABLE IF NOT EXISTS dn_project_release (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id      BIGINT NOT NULL,
  version_no      INT NOT NULL COMMENT '项目内自增版本号',
  title           VARCHAR(256) DEFAULT NULL,
  content         MEDIUMTEXT COMMENT '发布内容快照',
  target_env      VARCHAR(10) DEFAULT 'PROD',
  status          VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/RELEASED/ROLLED_BACK',
  submitted_by    VARCHAR(64) DEFAULT NULL,
  submitted_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  approver        VARCHAR(64) DEFAULT NULL,
  approved_at     DATETIME DEFAULT NULL,
  approve_comment VARCHAR(512) DEFAULT NULL,
  released_at     DATETIME DEFAULT NULL,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_project_version (project_id, version_no),
  INDEX idx_pr_project (project_id),
  INDEX idx_pr_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目发布版本';
