-- ============================================================
-- 56_mdm_golden.sql  主数据黄金记录(Golden Record)
-- 属性值以 JSON 柔性存储，适配各实体动态 schema
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_golden_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_id     BIGINT NOT NULL       COMMENT '所属实体',
  biz_key       VARCHAR(256)          COMMENT '业务主键值(展示/去重,取关键属性值)',
  data_json     TEXT                  COMMENT '全属性值JSON {attrCode:value}',
  status        VARCHAR(16) DEFAULT 'draft' COMMENT 'draft草稿/active生效/inactive停用',
  version       INT DEFAULT 1         COMMENT '版本号',
  source_system VARCHAR(64)           COMMENT '来源系统',
  created_by    VARCHAR(50),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_golden_entity (entity_id),
  KEY idx_mdm_golden_status (status)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据黄金记录';

-- ---------------- 种子示例(客户实体的两条黄金记录) ----------------
INSERT INTO dn_mdm_golden_record (entity_id, biz_key, data_json, status, version, source_system, created_by)
SELECT e.id, '阿里巴巴(中国)有限公司',
  '{"cust_id":"C00001","cust_name":"阿里巴巴(中国)有限公司","credit_code":"913301000000000001","cust_type":"企业","reg_date":"1999-09-09"}',
  'active', 1, 'CRM', 'system'
FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_golden_record g WHERE g.entity_id=e.id AND g.biz_key='阿里巴巴(中国)有限公司');

INSERT INTO dn_mdm_golden_record (entity_id, biz_key, data_json, status, version, source_system, created_by)
SELECT e.id, '腾讯科技(深圳)有限公司',
  '{"cust_id":"C00002","cust_name":"腾讯科技(深圳)有限公司","credit_code":"914403000000000002","cust_type":"企业","reg_date":"1998-11-11"}',
  'draft', 1, 'ERP', 'system'
FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_golden_record g WHERE g.entity_id=e.id AND g.biz_key='腾讯科技(深圳)有限公司');
