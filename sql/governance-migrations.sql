-- 数据治理迁移合并包 (M2-M12, 按序, 幂等可重复执行)
-- 用法: mysql -h<host> -P<port> -uroot -p datanote < sql/governance-migrations.sql

-- ===== 32_metadata_collect =====
-- 数据治理 M2：元数据采集扩列 + 采集日志（幂等，按 information_schema 守护）
USE datanote;

-- dn_table_meta 扩列（仅 db_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_table_meta' AND COLUMN_NAME = 'db_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_table_meta
     ADD COLUMN db_type VARCHAR(20) DEFAULT NULL COMMENT ''来源类型 MYSQL/DORIS'',
     ADD COLUMN table_type VARCHAR(30) DEFAULT NULL COMMENT ''表类型 BASE TABLE/VIEW'',
     ADD COLUMN size_bytes BIGINT DEFAULT NULL COMMENT ''数据体量(字节)'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- dn_column_meta 扩列（仅 data_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_column_meta' AND COLUMN_NAME = 'data_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_column_meta
     ADD COLUMN data_type VARCHAR(120) DEFAULT NULL COMMENT ''物理类型(如 varchar(50))'',
     ADD COLUMN column_key VARCHAR(16) DEFAULT NULL COMMENT ''键标识 PRI/MUL/UNI'',
     ADD COLUMN is_nullable VARCHAR(8) DEFAULT NULL COMMENT ''是否可空 YES/NO'',
     ADD COLUMN ordinal INT DEFAULT NULL COMMENT ''字段序号'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 采集日志
CREATE TABLE IF NOT EXISTS dn_meta_collect_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  datasource_id BIGINT DEFAULT NULL COMMENT '数据源ID(0=Doris数仓)',
  db_type       VARCHAR(20) DEFAULT NULL COMMENT 'MYSQL/DORIS',
  scope         VARCHAR(200) DEFAULT NULL COMMENT '采集范围(all 或 库名)',
  table_count   INT DEFAULT 0,
  column_count  INT DEFAULT 0,
  status        VARCHAR(20) DEFAULT NULL COMMENT 'success/error',
  message       TEXT,
  duration_ms   BIGINT DEFAULT 0,
  started_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  finished_at   DATETIME DEFAULT NULL,
  INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据采集日志';

-- ===== 33_lineage_edge =====
-- 数据治理 M3：统一血缘边表（表级 + 字段级）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_lineage_edge (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  level_type     VARCHAR(10)  NOT NULL COMMENT 'TABLE / COLUMN',
  src_db         VARCHAR(100) NOT NULL DEFAULT '',
  src_table      VARCHAR(200) NOT NULL DEFAULT '',
  src_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  dst_db         VARCHAR(100) NOT NULL DEFAULT '',
  dst_table      VARCHAR(200) NOT NULL DEFAULT '',
  dst_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  transform_type VARCHAR(20)  DEFAULT 'DIRECT' COMMENT 'DIRECT/TRANSFORM/MASK',
  source         VARCHAR(16)  NOT NULL DEFAULT 'MAPPING' COMMENT 'MAPPING/SQL/SCHEDULE/MANUAL',
  confidence     INT          DEFAULT 100 COMMENT '置信度 0-100',
  job_id         BIGINT       DEFAULT NULL COMMENT '来源同步任务ID',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- 前缀索引：8 列全长在 utf8mb4 下超 InnoDB 3072 字节上限，按前缀取唯一(库表列名极少超此长度)
  UNIQUE KEY uk_edge (level_type, src_db(64), src_table(128), src_column(128), dst_db(64), dst_table(128), dst_column(128), source),
  INDEX idx_src (src_db, src_table),
  INDEX idx_dst (dst_db, dst_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据血缘边';

-- ===== 35_quality_enhance =====
-- 数据治理 M5：质量规则阈值/强弱/维度扩列（幂等，按 information_schema 守护）
USE datanote;

-- dn_quality_rule 扩列（仅 pass_threshold 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_quality_rule' AND COLUMN_NAME = 'pass_threshold');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_quality_rule
     ADD COLUMN pass_threshold DECIMAL(5,2) DEFAULT 100 COMMENT ''通过率阈值(%)，实际通过率<它判 failed'',
     ADD COLUMN block_downstream TINYINT DEFAULT 0 COMMENT ''强规则:1 失败阻塞下游 0 弱规则'',
     ADD COLUMN dimension VARCHAR(20) DEFAULT NULL COMMENT ''质量维度 完整性/准确性/一致性/唯一性/及时性/有效性''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 36_rbac =====
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

-- ===== 37_data_standard =====
-- 数据治理 M7：数据标准（数据元 + 命名词根 + 码表 + 落标稽核）。幂等，CREATE TABLE IF NOT EXISTS。
USE datanote;

-- 数据元（数据标准核心）
CREATE TABLE IF NOT EXISTS dn_data_element (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  element_code   VARCHAR(80)  NOT NULL COMMENT '数据元编码(唯一,落标按列名比对)',
  name_cn        VARCHAR(120) DEFAULT NULL COMMENT '中文名称',
  data_type      VARCHAR(60)  DEFAULT NULL COMMENT '标准数据类型(如 varchar/bigint)',
  length         INT          DEFAULT NULL COMMENT '标准长度',
  value_domain   VARCHAR(500) DEFAULT NULL COMMENT '值域(取值范围/枚举)',
  sensitive_type VARCHAR(40)  DEFAULT NULL COMMENT '敏感类型建议',
  security_level VARCHAR(40)  DEFAULT NULL COMMENT '密级建议',
  description    VARCHAR(500) DEFAULT NULL COMMENT '描述',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_element_code (element_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据元';

-- 命名词根（中英）
CREATE TABLE IF NOT EXISTS dn_word_root (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  word_cn   VARCHAR(60) DEFAULT NULL COMMENT '中文词',
  word_en   VARCHAR(60) DEFAULT NULL COMMENT '英文全称',
  abbr      VARCHAR(40) DEFAULT NULL COMMENT '英文缩写',
  category  VARCHAR(40) DEFAULT NULL COMMENT '分类',
  KEY idx_word_en (word_en),
  KEY idx_abbr (abbr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='命名词根';

-- 码表（参考数据）
CREATE TABLE IF NOT EXISTS dn_code_dict (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  dict_code   VARCHAR(80)  NOT NULL COMMENT '码表编码(唯一)',
  dict_name   VARCHAR(120) DEFAULT NULL COMMENT '码表名称',
  description VARCHAR(500) DEFAULT NULL COMMENT '描述',
  UNIQUE KEY uk_dict_code (dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='码表';

-- 码表明细项
CREATE TABLE IF NOT EXISTS dn_code_dict_item (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  dict_id    BIGINT       NOT NULL COMMENT '所属码表ID',
  item_key   VARCHAR(120) DEFAULT NULL COMMENT '码值',
  item_value VARCHAR(255) DEFAULT NULL COMMENT '含义',
  sort       INT          DEFAULT 0 COMMENT '排序',
  KEY idx_dict_id (dict_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='码表明细项';

-- 落标稽核结果
CREATE TABLE IF NOT EXISTS dn_standard_check_run (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope           VARCHAR(200) DEFAULT NULL COMMENT '稽核范围(all 或 库名)',
  total_count     INT          DEFAULT 0 COMMENT '稽核字段总数',
  violation_count INT          DEFAULT 0 COMMENT '不合规数',
  pass_rate       DECIMAL(5,2) DEFAULT 0 COMMENT '落标率(%)',
  detail          TEXT COMMENT '不合规清单(JSON)',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='落标稽核结果';

-- 预置常用命名词根（仅当词根表为空时插入，避免重复）
INSERT INTO dn_word_root (word_cn, word_en, abbr, category)
SELECT * FROM (
  SELECT '标识' AS word_cn, 'id' AS word_en, 'id' AS abbr, '通用' AS category
  UNION ALL SELECT '编号', 'number', 'no', '通用'
  UNION ALL SELECT '名称', 'name', 'name', '通用'
  UNION ALL SELECT '编码', 'code', 'code', '通用'
  UNION ALL SELECT '金额', 'amount', 'amt', '财务'
  UNION ALL SELECT '数量', 'quantity', 'qty', '通用'
  UNION ALL SELECT '时间', 'time', 'time', '时间'
  UNION ALL SELECT '日期', 'date', 'date', '时间'
  UNION ALL SELECT '状态', 'status', 'status', '通用'
  UNION ALL SELECT '类型', 'type', 'type', '通用'
  UNION ALL SELECT '描述', 'description', 'desc', '通用'
  UNION ALL SELECT '用户', 'user', 'user', '主体'
  UNION ALL SELECT '订单', 'order', 'order', '业务'
  UNION ALL SELECT '创建', 'create', 'create', '操作'
  UNION ALL SELECT '更新', 'update', 'update', '操作'
  UNION ALL SELECT '是否', 'is', 'is', '通用'
  UNION ALL SELECT '手机', 'phone', 'phone', '联系'
  UNION ALL SELECT '邮箱', 'email', 'email', '联系'
  UNION ALL SELECT '地址', 'address', 'addr', '联系'
  UNION ALL SELECT '备注', 'remark', 'remark', '通用'
) seed
WHERE NOT EXISTS (SELECT 1 FROM dn_word_root LIMIT 1);

-- ===== 38_classification =====
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

-- ===== 39_masking =====
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

-- ===== 40_lifecycle =====
-- 数据治理 M10：资产盘点 + 生命周期 + 成本 + 无用表识别（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）
USE datanote;

-- 生命周期策略：冷热分层 / TTL / 归档；应用后自动下发 Doris 原生 DDL
CREATE TABLE IF NOT EXISTS dn_lifecycle_policy (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  db_name     VARCHAR(128) NOT NULL COMMENT '库名',
  table_name  VARCHAR(128) NOT NULL COMMENT '表名',
  policy_type VARCHAR(16)  NOT NULL COMMENT 'HOT_COLD/TTL/ARCHIVE',
  cold_days   INT          DEFAULT NULL COMMENT '冷下沉天数(HOT_COLD)',
  ttl_days    INT          DEFAULT NULL COMMENT '保留天数(TTL/dynamic_partition)',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  status      VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/ACTIVE/PENDING/FAILED/DROP_PENDING/DROPPED',
  ddl_text    TEXT         DEFAULT NULL COMMENT '最近一次下发的DDL',
  last_msg    VARCHAR(500) DEFAULT NULL COMMENT '最近一次结果/异常信息',
  drop_due_at DATETIME     DEFAULT NULL COMMENT '销毁到期时间(软删宽限期)',
  approver    VARCHAR(80)  DEFAULT NULL COMMENT '销毁审批人',
  reason      VARCHAR(255) DEFAULT NULL COMMENT '销毁原因',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_db_table_type (db_name, table_name, policy_type),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生命周期策略';

-- 资产采集快照：体量 / 行数 / 最近访问 / 成本
CREATE TABLE IF NOT EXISTS dn_asset_stat (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id  BIGINT       DEFAULT NULL COMMENT '关联 dn_table_meta.id',
  db_name        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '库名',
  table_name     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '表名',
  size_bytes     BIGINT       DEFAULT NULL COMMENT '体量(字节)',
  row_count      BIGINT       DEFAULT NULL COMMENT '行数',
  last_access_at DATETIME     DEFAULT NULL COMMENT '最近访问时间(兜底用采集时间)',
  cost_estimate  DECIMAL(18,4) DEFAULT NULL COMMENT '估算成本(单价×体量)',
  collected_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
  INDEX idx_table_meta (table_meta_id),
  INDEX idx_db_table (db_name, table_name),
  INDEX idx_collected_at (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产采集快照(体量/访问/成本)';

-- 可配项：写入 dn_system_config（成本单价/销毁宽限期/无用表久未访问阈值）
INSERT INTO dn_system_config (config_key, config_value, description) VALUES
  ('lifecycle.cost.unit_price',   '0.05', '存储单价(元/GB/月)'),
  ('lifecycle.drop.grace_days',   '30',   '销毁软删宽限期(天)'),
  ('lifecycle.unused.access_days','90',   '无用表久未访问阈值(天)')
ON DUPLICATE KEY UPDATE description = VALUES(description);

-- ===== 41_governance_health =====
-- 数据治理 M11：治理健康分 + 工单闭环 + DCMM 成熟度自评（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）
USE datanote;

-- 治理项规则库：五维打分项（配置表驱动，权重可调）
CREATE TABLE IF NOT EXISTS dn_governance_metric (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  dimension   VARCHAR(20)  NOT NULL COMMENT '规范/质量/安全/生命周期/血缘',
  metric_code VARCHAR(40)  NOT NULL COMMENT '指标编码',
  metric_name VARCHAR(80)  NOT NULL COMMENT '指标名称',
  weight      DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '权重(同维度内细分;维度总权重由各项汇总)',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  UNIQUE KEY uk_dim_code (dimension, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理项规则库(五维打分项)';

-- 预置默认权重：规范20/质量25/安全25/生命周期15/血缘15（每维度一个兜底项，同维度内可再加项细分）
INSERT INTO dn_governance_metric (dimension, metric_code, metric_name, weight, enabled) VALUES
  ('规范',     'STANDARD_PASS_RATE', '落标率',         20, 1),
  ('质量',     'QUALITY_PASS_RATE',  '质量通过率',     25, 1),
  ('安全',     'SECURITY_COVERAGE',  '分级覆盖率',     25, 1),
  ('生命周期', 'LIFECYCLE_COMPLETE', '资产治理完整度', 15, 1),
  ('血缘',     'LINEAGE_COVERAGE',   '血缘覆盖率',     15, 1)
ON DUPLICATE KEY UPDATE metric_name = VALUES(metric_name), weight = VALUES(weight);

-- 健康分快照（时序）
CREATE TABLE IF NOT EXISTS dn_governance_score (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  score_date  DATE         NOT NULL COMMENT '快照日期',
  total_score DECIMAL(6,2) NOT NULL COMMENT '总分0-100',
  dim_scores  JSON         DEFAULT NULL COMMENT '各维度分 {维度:分}',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_score_date (score_date),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康分快照(时序)';

-- 治理工单单一事实表：问题→工单→整改→复检闭环
CREATE TABLE IF NOT EXISTS dn_governance_issue (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  issue_type  VARCHAR(40)  NOT NULL COMMENT '问题类型(STANDARD/QUALITY/SECURITY/LINEAGE/LIFECYCLE/OTHER)',
  dimension   VARCHAR(20)  DEFAULT NULL COMMENT '所属维度',
  object_ref  VARCHAR(255) DEFAULT NULL COMMENT '对象引用(库.表.列等)',
  title       VARCHAR(200) NOT NULL COMMENT '标题',
  description TEXT         DEFAULT NULL COMMENT '描述',
  severity    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
  owner       VARCHAR(80)  DEFAULT NULL COMMENT '负责人',
  status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FIXING/RESOLVED/VERIFIED/CLOSED',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_status (status),
  INDEX idx_owner (owner),
  INDEX idx_dimension (dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理工单单一事实表';

-- DCMM 八大域成熟度自评
CREATE TABLE IF NOT EXISTS dn_maturity_assessment (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain      VARCHAR(40)  NOT NULL COMMENT 'DCMM八大域:数据战略/数据治理/数据架构/数据应用/数据安全/数据质量/数据标准/数据生存周期',
  score       DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '自评分0-100',
  level       INT          NOT NULL DEFAULT 1 COMMENT '成熟度等级1-5',
  note        VARCHAR(500) DEFAULT NULL COMMENT '备注',
  assessed_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_domain (domain),
  INDEX idx_assessed_at (assessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DCMM八大域成熟度自评';

-- ===== 42_audit =====
-- 数据治理 M12：全局审计中心（幂等：CREATE TABLE IF NOT EXISTS；只增不改，无 update 列/触发器）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_audit_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_name   VARCHAR(80)  NOT NULL DEFAULT 'anonymous' COMMENT '操作人(匿名记 anonymous)',
  action_type VARCHAR(20)  NOT NULL COMMENT 'LOGIN/DATA_ACCESS/EXPORT/PERM_CHANGE/META_CHANGE/RULE_CHANGE/LABEL_CHANGE/OTHER',
  target      VARCHAR(255) DEFAULT NULL COMMENT '操作对象(预留)',
  method      VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法',
  path        VARCHAR(255) DEFAULT NULL COMMENT '请求路径',
  ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
  status      INT          DEFAULT NULL COMMENT '响应状态码',
  detail      TEXT         DEFAULT NULL COMMENT '详情(耗时/查询串等)',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  INDEX idx_created_at (created_at),
  INDEX idx_user_name (user_name),
  INDEX idx_action_type (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局审计日志(只增不改)';
