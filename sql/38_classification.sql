-- 数据治理 M8：分类分级 + 敏感识别（幂等：CREATE TABLE IF NOT EXISTS + information_schema 守护加列）
USE datanote;

-- 分级模型字典：国家三级(NATIONAL) + 金融五级(FINANCE)
CREATE TABLE IF NOT EXISTS dn_classification_level (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  scheme     VARCHAR(20)  NOT NULL COMMENT 'NATIONAL/FINANCE',
  level_code VARCHAR(20)  NOT NULL COMMENT '编码',
  level_name VARCHAR(40)  NOT NULL COMMENT '名称',
  sort       INT          NOT NULL DEFAULT 0 COMMENT '由低到高，越大密级越高',
  UNIQUE KEY uk_scheme_code (scheme, level_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分级模型字典';

INSERT INTO dn_classification_level (scheme, level_code, level_name, sort) VALUES
  ('NATIONAL', 'GENERAL', '一般', 1),
  ('NATIONAL', 'IMPORTANT', '重要', 2),
  ('NATIONAL', 'CORE', '核心', 3),
  ('FINANCE', 'L1', 'L1', 1),
  ('FINANCE', 'L2', 'L2', 2),
  ('FINANCE', 'L3', 'L3', 3),
  ('FINANCE', 'L4', 'L4', 4),
  ('FINANCE', 'L5', 'L5', 5)
ON DUPLICATE KEY UPDATE level_name = VALUES(level_name), sort = VALUES(sort);

-- 敏感识别规则
CREATE TABLE IF NOT EXISTS dn_sensitive_rule (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_name      VARCHAR(80)  NOT NULL COMMENT '规则名',
  match_type     VARCHAR(16)  NOT NULL COMMENT 'COLUMN_NAME/REGEX/VALIDATOR',
  pattern        VARCHAR(255) NOT NULL COMMENT '关键词(逗号分隔)/正则/校验器名',
  sensitive_type VARCHAR(40)  NOT NULL COMMENT 'PHONE/EMAIL/ID_CARD/BANK_CARD/USCC...',
  suggest_level  VARCHAR(40)  DEFAULT NULL COMMENT '建议密级(level_name)',
  enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rule (rule_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感识别规则';

INSERT INTO dn_sensitive_rule (rule_name, match_type, pattern, sensitive_type, suggest_level, enabled) VALUES
  -- 列名关键词（强信号）
  ('手机-列名',   'COLUMN_NAME', 'phone,mobile,tel,手机,电话',              'PHONE',     '重要', 1),
  ('邮箱-列名',   'COLUMN_NAME', 'email,mail,邮箱',                         'EMAIL',     '一般', 1),
  ('身份证-列名', 'COLUMN_NAME', 'idcard,id_card,identity,身份证',          'ID_CARD',   '核心', 1),
  ('银行卡-列名', 'COLUMN_NAME', 'bankcard,bank_card,card_no,银行卡,卡号',  'BANK_CARD', '核心', 1),
  ('信用代码-列名','COLUMN_NAME','uscc,credit_code,社会信用代码',           'USCC',      '重要', 1),
  -- 取值正则 / 校验位
  ('手机-正则',   'REGEX',     '^1[3-9]\\d{9}$',                          'PHONE',     '重要', 1),
  ('邮箱-正则',   'REGEX',     '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$', 'EMAIL', '一般', 1),
  ('身份证-校验', 'VALIDATOR', 'ID_CARD',                                 'ID_CARD',   '核心', 1),
  ('银行卡-Luhn', 'VALIDATOR', 'BANKCARD',                                'BANK_CARD', '核心', 1),
  ('信用代码-校验','VALIDATOR','USCC',                                     'USCC',      '重要', 1)
ON DUPLICATE KEY UPDATE pattern = VALUES(pattern), sensitive_type = VALUES(sensitive_type),
  suggest_level = VALUES(suggest_level);

-- 打标 / 降级审批留痕
CREATE TABLE IF NOT EXISTS dn_label_audit (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id  BIGINT       DEFAULT NULL,
  column_name    VARCHAR(200) NOT NULL DEFAULT '',
  old_level      VARCHAR(40)  DEFAULT NULL,
  new_level      VARCHAR(40)  DEFAULT NULL,
  sensitive_type VARCHAR(40)  DEFAULT NULL,
  operator       VARCHAR(80)  DEFAULT NULL,
  reason         VARCHAR(255) DEFAULT NULL,
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_table_meta (table_meta_id),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打标/降级审批留痕';

-- dn_column_meta 扩列（仅 security_level 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_column_meta' AND COLUMN_NAME = 'security_level');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_column_meta
     ADD COLUMN security_level VARCHAR(20) DEFAULT NULL COMMENT ''密级(level_name)'',
     ADD COLUMN sensitive_type VARCHAR(40) DEFAULT NULL COMMENT ''敏感类型''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
