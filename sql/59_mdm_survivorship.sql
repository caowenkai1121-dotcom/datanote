-- ============================================================
-- 59_mdm_survivorship.sql  主数据管理(MDM) — 存活性规则(Survivorship)
-- 定义某实体每个属性在黄金记录合并时的存活策略，自动选最佳值
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_survivorship_rule (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_id       BIGINT NOT NULL       COMMENT '所属实体',
  attr_code       VARCHAR(64)  NOT NULL COMMENT '属性编码',
  attr_name       VARCHAR(128)          COMMENT '属性名称(冗余,展示用)',
  strategy        VARCHAR(32)  NOT NULL DEFAULT 'latest' COMMENT '存活策略:latest最新/most_complete最完整/source_priority源优先',
  source_priority VARCHAR(512)          COMMENT '源系统优先级清单(逗号分隔,仅source_priority策略用),可空',
  priority        INT DEFAULT 0         COMMENT '规则优先级(数字越小越优先)',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_surv_entity (entity_id),
  UNIQUE KEY uk_mdm_surv_attr (entity_id, attr_code)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据存活性规则';

-- ---------------- 种子示例数据(挂到客户实体核心属性,便于首次进入即有可视内容) ----------------
INSERT INTO dn_mdm_survivorship_rule (entity_id, attr_code, attr_name, strategy, source_priority, priority)
SELECT e.id, 'cust_name','客户名称','source_priority','CRM,ERP',1 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_survivorship_rule r WHERE r.entity_id=e.id AND r.attr_code='cust_name');

INSERT INTO dn_mdm_survivorship_rule (entity_id, attr_code, attr_name, strategy, source_priority, priority)
SELECT e.id, 'credit_code','统一社会信用代码','most_complete',NULL,2 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_survivorship_rule r WHERE r.entity_id=e.id AND r.attr_code='credit_code');

INSERT INTO dn_mdm_survivorship_rule (entity_id, attr_code, attr_name, strategy, source_priority, priority)
SELECT e.id, 'reg_date','注册日期','latest',NULL,3 FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_survivorship_rule r WHERE r.entity_id=e.id AND r.attr_code='reg_date');
