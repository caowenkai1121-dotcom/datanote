-- ============================================================
-- 55_mdm.sql  主数据管理(MDM) — 域/实体/属性 建模层
-- 主数据域(domain) → 实体(entity) → 属性(attribute) 三级建模
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_domain (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_code  VARCHAR(64)  NOT NULL COMMENT '域编码(唯一)',
  domain_name  VARCHAR(128) NOT NULL COMMENT '域名称',
  category     VARCHAR(32)           COMMENT '业务类别:客户/产品/供应商/组织/财务/其他',
  owner        VARCHAR(64)           COMMENT '域负责人',
  description  VARCHAR(512)          COMMENT '描述',
  status       TINYINT DEFAULT 1     COMMENT '1启用 0停用',
  created_by   VARCHAR(50),
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mdm_domain_code (domain_code)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据域';

CREATE TABLE IF NOT EXISTS dn_mdm_entity (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_id    BIGINT NOT NULL       COMMENT '所属域',
  entity_code  VARCHAR(64)  NOT NULL COMMENT '实体编码',
  entity_name  VARCHAR(128) NOT NULL COMMENT '实体名称',
  description  VARCHAR(512)          COMMENT '描述',
  status       TINYINT DEFAULT 1     COMMENT '1启用 0停用',
  attr_count   INT DEFAULT 0         COMMENT '属性数(冗余,展示用)',
  created_by   VARCHAR(50),
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_entity_domain (domain_id),
  UNIQUE KEY uk_mdm_entity_code (domain_id, entity_code)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据实体';

CREATE TABLE IF NOT EXISTS dn_mdm_attribute (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_id     BIGINT NOT NULL       COMMENT '所属实体',
  attr_code     VARCHAR(64)  NOT NULL COMMENT '属性编码',
  attr_name     VARCHAR(128) NOT NULL COMMENT '属性名称',
  data_type     VARCHAR(32) DEFAULT 'STRING' COMMENT 'STRING/INT/DECIMAL/DATE/BOOLEAN/ENUM/REFERENCE',
  length_limit  INT                   COMMENT '长度限制',
  required      TINYINT DEFAULT 0     COMMENT '是否必填',
  is_key        TINYINT DEFAULT 0     COMMENT '是否关键字段(匹配用)',
  is_unique     TINYINT DEFAULT 0     COMMENT '是否唯一',
  enum_values   VARCHAR(1024)         COMMENT 'ENUM候选值(逗号分隔)',
  default_value VARCHAR(256)          COMMENT '默认值',
  description   VARCHAR(512)          COMMENT '描述',
  sort_order    INT DEFAULT 0         COMMENT '排序',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_attr_entity (entity_id),
  UNIQUE KEY uk_mdm_attr_code (entity_id, attr_code)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据实体属性';

-- ---------------- 种子示例数据(便于首次进入即有可视内容) ----------------
INSERT INTO dn_mdm_domain (domain_code, domain_name, category, owner, description, status, created_by)
SELECT * FROM (SELECT 'CUSTOMER','客户主数据','客户','数据治理部','统一客户单一视图(C360)' AS d, 1, 'system') t
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_domain WHERE domain_code='CUSTOMER');
INSERT INTO dn_mdm_domain (domain_code, domain_name, category, owner, description, status, created_by)
SELECT * FROM (SELECT 'PRODUCT','产品主数据','产品','商品中心','产品/物料黄金记录' AS d, 1, 'system') t
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_domain WHERE domain_code='PRODUCT');
INSERT INTO dn_mdm_domain (domain_code, domain_name, category, owner, description, status, created_by)
SELECT * FROM (SELECT 'SUPPLIER','供应商主数据','供应商','采购中心','供应商主数据与资质' AS d, 1, 'system') t
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_domain WHERE domain_code='SUPPLIER');

-- 客户域下的客户实体
INSERT INTO dn_mdm_entity (domain_id, entity_code, entity_name, description, status, attr_count, created_by)
SELECT d.id, 'customer','客户','客户黄金记录',1,5,'system' FROM dn_mdm_domain d
WHERE d.domain_code='CUSTOMER' AND NOT EXISTS (SELECT 1 FROM dn_mdm_entity e WHERE e.domain_id=d.id AND e.entity_code='customer');

-- 客户实体的核心属性
INSERT INTO dn_mdm_attribute (entity_id, attr_code, attr_name, data_type, length_limit, required, is_key, is_unique, description, sort_order)
SELECT e.id, 'cust_id','客户ID','STRING',32,1,0,1,'全局唯一客户编号',1 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer' AND NOT EXISTS (SELECT 1 FROM dn_mdm_attribute a WHERE a.entity_id=e.id AND a.attr_code='cust_id');
INSERT INTO dn_mdm_attribute (entity_id, attr_code, attr_name, data_type, length_limit, required, is_key, is_unique, description, sort_order)
SELECT e.id, 'cust_name','客户名称','STRING',128,1,1,0,'客户全称(关键匹配字段)',2 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer' AND NOT EXISTS (SELECT 1 FROM dn_mdm_attribute a WHERE a.entity_id=e.id AND a.attr_code='cust_name');
INSERT INTO dn_mdm_attribute (entity_id, attr_code, attr_name, data_type, length_limit, required, is_key, is_unique, description, sort_order)
SELECT e.id, 'credit_code','统一社会信用代码','STRING',18,0,1,1,'企业唯一标识(关键匹配字段)',3 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer' AND NOT EXISTS (SELECT 1 FROM dn_mdm_attribute a WHERE a.entity_id=e.id AND a.attr_code='credit_code');
INSERT INTO dn_mdm_attribute (entity_id, attr_code, attr_name, data_type, length_limit, required, is_key, is_unique, enum_values, description, sort_order)
SELECT e.id, 'cust_type','客户类型','ENUM',NULL,0,0,0,'企业,个人,政府','客户类型枚举',4 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer' AND NOT EXISTS (SELECT 1 FROM dn_mdm_attribute a WHERE a.entity_id=e.id AND a.attr_code='cust_type');
INSERT INTO dn_mdm_attribute (entity_id, attr_code, attr_name, data_type, length_limit, required, is_key, is_unique, description, sort_order)
SELECT e.id, 'reg_date','注册日期','DATE',NULL,0,0,0,'客户注册日期',5 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer' AND NOT EXISTS (SELECT 1 FROM dn_mdm_attribute a WHERE a.entity_id=e.id AND a.attr_code='reg_date');
