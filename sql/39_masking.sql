-- 数据治理 M9：查询期动态脱敏 + 行级权限（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）
USE datanote;

-- 脱敏策略：按 sensitive_type（敏感类型）或 column_name（具体列）维度，二选一命中。
-- masking_func：MASK(掩码)/HASH(MD5)/REPLACE(常量)/RANGE(区间分桶)。
CREATE TABLE IF NOT EXISTS dn_masking_policy (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_name    VARCHAR(80)  NOT NULL COMMENT '策略名',
  match_dim      VARCHAR(16)  NOT NULL COMMENT '维度: SENSITIVE_TYPE / COLUMN',
  sensitive_type VARCHAR(40)  DEFAULT NULL COMMENT 'SENSITIVE_TYPE 维度: PHONE/EMAIL/ID_CARD/BANK_CARD/USCC',
  db_name        VARCHAR(128) DEFAULT NULL COMMENT 'COLUMN 维度: 库名',
  table_name     VARCHAR(128) DEFAULT NULL COMMENT 'COLUMN 维度: 表名',
  column_name    VARCHAR(128) DEFAULT NULL COMMENT 'COLUMN 维度: 列名',
  masking_func   VARCHAR(16)  NOT NULL DEFAULT 'MASK' COMMENT 'MASK/HASH/REPLACE/RANGE',
  enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_policy_name (policy_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脱敏策略';

-- 预置：按 sensitive_type 的默认脱敏（手机/邮箱/身份证/银行卡 → MASK）
INSERT INTO dn_masking_policy (policy_name, match_dim, sensitive_type, masking_func, enabled) VALUES
  ('手机脱敏',   'SENSITIVE_TYPE', 'PHONE',     'MASK', 1),
  ('邮箱脱敏',   'SENSITIVE_TYPE', 'EMAIL',     'MASK', 1),
  ('身份证脱敏', 'SENSITIVE_TYPE', 'ID_CARD',   'MASK', 1),
  ('银行卡脱敏', 'SENSITIVE_TYPE', 'BANK_CARD', 'MASK', 1)
ON DUPLICATE KEY UPDATE masking_func = VALUES(masking_func), enabled = VALUES(enabled);

-- 行级权限：按角色 × 库.表 注入行过滤 WHERE 片段（AND 收紧）。
CREATE TABLE IF NOT EXISTS dn_row_policy (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码（dn_role.role_code）',
  db_name     VARCHAR(128) NOT NULL COMMENT '库名',
  table_name  VARCHAR(128) NOT NULL COMMENT '表名',
  row_filter  VARCHAR(512) NOT NULL COMMENT '行过滤 WHERE 片段（如 region = ''EAST''）',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_role (role_code),
  INDEX idx_table (db_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行级权限策略';
</content>
