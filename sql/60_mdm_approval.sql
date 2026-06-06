-- ============================================================
-- 60_mdm_approval.sql  主数据变更审批(Change Approval)
-- 对黄金记录的变更(新增/修改/删除)走审批流，变更内容以 JSON 柔性存储
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_change_request (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_id        BIGINT NOT NULL       COMMENT '所属实体',
  golden_record_id BIGINT                COMMENT '关联黄金记录(create时可空)',
  change_type      VARCHAR(16) NOT NULL  COMMENT 'create新增/update修改/delete删除',
  biz_key          VARCHAR(256)          COMMENT '业务主键(展示用)',
  payload_json     TEXT                  COMMENT '变更内容JSON {attrCode:value}',
  reason           VARCHAR(512)          COMMENT '变更原因',
  status           VARCHAR(16) DEFAULT 'pending' COMMENT 'pending待审批/approved已批准/rejected已驳回',
  requested_by     VARCHAR(64)           COMMENT '申请人',
  reviewer         VARCHAR(64)           COMMENT '审批人',
  review_comment   VARCHAR(512)          COMMENT '审批意见',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_change_entity (entity_id),
  KEY idx_mdm_change_status (status)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据变更审批请求';

-- ---------------- 种子示例(客户实体的两条待审批变更) ----------------
INSERT INTO dn_mdm_change_request (entity_id, golden_record_id, change_type, biz_key, payload_json, reason, status, requested_by)
SELECT e.id, NULL, 'create', '小米科技有限责任公司',
  '{"cust_id":"C00003","cust_name":"小米科技有限责任公司","credit_code":"911101080000000003","cust_type":"企业","reg_date":"2010-03-03"}',
  '新增客户主数据，已完成尽职调查', 'pending', 'zhangsan'
FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_change_request r WHERE r.entity_id=e.id AND r.change_type='create' AND r.biz_key='小米科技有限责任公司');

INSERT INTO dn_mdm_change_request (entity_id, golden_record_id, change_type, biz_key, payload_json, reason, status, requested_by)
SELECT e.id, g.id, 'update', g.biz_key,
  '{"cust_name":"阿里巴巴(中国)有限公司","cust_type":"企业","reg_date":"1999-09-09","credit_code":"913301000000000001"}',
  '更正注册信息，与工商登记保持一致', 'pending', 'lisi'
FROM dn_mdm_entity e
JOIN dn_mdm_domain d ON e.domain_id=d.id
JOIN dn_mdm_golden_record g ON g.entity_id=e.id AND g.biz_key='阿里巴巴(中国)有限公司'
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_change_request r WHERE r.entity_id=e.id AND r.change_type='update' AND r.golden_record_id=g.id);
