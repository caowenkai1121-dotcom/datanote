-- ============================================================
-- 61_mdm_refdata.sql  参考数据/码表(Reference Data)
-- 系统级枚举与码表(国家/地区/行业分类等), 支持树形 parent_code
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_reference (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  category     VARCHAR(64)  NOT NULL COMMENT '码表类别 如 CUSTOMER_TYPE/REGION',
  code         VARCHAR(64)  NOT NULL COMMENT '码值',
  name         VARCHAR(128) NOT NULL COMMENT '码值名称',
  parent_code  VARCHAR(64)           COMMENT '父级码值(树形,可空)',
  sort_order   INT DEFAULT 0         COMMENT '排序',
  status       TINYINT DEFAULT 1     COMMENT '1启用 0停用',
  description  VARCHAR(512)          COMMENT '描述',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_ref_category (category),
  KEY idx_mdm_ref_parent (category, parent_code),
  UNIQUE KEY uk_mdm_ref_code (category, code)
) DEFAULT CHARSET=utf8mb4 COMMENT='参考数据/码表';

-- ---------------- 种子示例数据 ----------------
-- 客户类型 CUSTOMER_TYPE
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'CUSTOMER_TYPE','ENTERPRISE','企业',NULL,1,1,'企业客户'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='CUSTOMER_TYPE' AND code='ENTERPRISE');
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'CUSTOMER_TYPE','PERSONAL','个人',NULL,2,1,'个人客户'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='CUSTOMER_TYPE' AND code='PERSONAL');
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'CUSTOMER_TYPE','GOVERNMENT','政府',NULL,3,1,'政府/事业单位客户'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='CUSTOMER_TYPE' AND code='GOVERNMENT');

-- 地区 REGION (省份示例, 树形: 省 → 市)
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'REGION','110000','北京市',NULL,1,1,'直辖市'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='REGION' AND code='110000');
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'REGION','310000','上海市',NULL,2,1,'直辖市'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='REGION' AND code='310000');
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'REGION','440000','广东省',NULL,3,1,'省'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='REGION' AND code='440000');
-- 广东省下的市(树形子级)
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'REGION','440100','广州市','440000',1,1,'广东省地级市'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='REGION' AND code='440100');
INSERT INTO dn_mdm_reference (category, code, name, parent_code, sort_order, status, description)
SELECT 'REGION','440300','深圳市','440000',2,1,'广东省地级市'
WHERE NOT EXISTS (SELECT 1 FROM dn_mdm_reference WHERE category='REGION' AND code='440300');
