-- 项目管理 V2 PM2-M4：公告 + 成员邀请（幂等）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_project_announcement (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  title       VARCHAR(256) NOT NULL,
  content     VARCHAR(2048) DEFAULT NULL,
  priority    VARCHAR(8) DEFAULT 'NORMAL' COMMENT 'HIGH/NORMAL',
  expire_at   DATETIME DEFAULT NULL,
  created_by  VARCHAR(64) DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ann_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目公告';

CREATE TABLE IF NOT EXISTS dn_project_invite (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  invitee     VARCHAR(64) DEFAULT NULL COMMENT '指定被邀请人(可空,凭码加入)',
  project_role VARCHAR(16) NOT NULL DEFAULT 'VIEWER',
  token       VARCHAR(64) NOT NULL,
  status      VARCHAR(12) DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED/CANCELED',
  created_by  VARCHAR(64) DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  handled_at  DATETIME DEFAULT NULL,
  handled_by  VARCHAR(64) DEFAULT NULL,
  UNIQUE KEY uk_invite_token (token),
  INDEX idx_inv_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员邀请';
