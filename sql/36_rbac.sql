-- 数据治理 M6：多用户 + RBAC 底座（幂等，可重复执行）
-- 预置：ADMIN 角色 + admin 用户（BCrypt 哈希，默认口令 admin123）+ ADMIN 全权限(perm_code='*')
USE datanote;

-- 用户表
CREATE TABLE IF NOT EXISTS dn_user (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  username    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希密码',
  nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态(1启用/0停用)',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC 用户';

-- 角色表
CREATE TABLE IF NOT EXISTS dn_role (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码',
  role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
  description VARCHAR(256) DEFAULT NULL COMMENT '角色描述',
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC 角色';

-- 用户-角色关联表
CREATE TABLE IF NOT EXISTS dn_user_role (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id  BIGINT NOT NULL COMMENT '用户ID',
  role_id  BIGINT NOT NULL COMMENT '角色ID',
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC 用户-角色';

-- 角色-权限关联表
CREATE TABLE IF NOT EXISTS dn_role_perm (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id   BIGINT NOT NULL COMMENT '角色ID',
  perm_code VARCHAR(64) NOT NULL COMMENT '权限点(* 表示全权限)',
  UNIQUE KEY uk_role_perm (role_id, perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC 角色-权限';

-- 预置 ADMIN 角色（幂等）
INSERT INTO dn_role (role_code, role_name, description)
VALUES ('ADMIN', '系统管理员', '内置超级管理员，拥有全部权限')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

-- 预置 admin 用户（BCrypt 哈希，默认口令 admin123；首次登录后请尽快修改）
INSERT INTO dn_user (username, password, nickname, status)
VALUES ('admin', '$2a$10$d3uzf5P/igk82XIDPUfTPeV6kLEceqlq18A2aSbocwl/FU1dKjZsm', '管理员', 1)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);

-- 预置 ADMIN 角色全权限 perm_code='*'（幂等）
INSERT INTO dn_role_perm (role_id, perm_code)
SELECT r.id, '*' FROM dn_role r WHERE r.role_code = 'ADMIN'
ON DUPLICATE KEY UPDATE perm_code = VALUES(perm_code);

-- 给 admin 用户绑定 ADMIN 角色（幂等）
INSERT INTO dn_user_role (user_id, role_id)
SELECT u.id, r.id FROM dn_user u, dn_role r
WHERE u.username = 'admin' AND r.role_code = 'ADMIN'
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
