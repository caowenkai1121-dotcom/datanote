-- ============================================================
-- 57_mdm_match_seed.sql  匹配去重演示种子
-- 插入一条与阿里巴巴(C00001)统一社会信用代码重复的记录，用于演示去重检测
-- ============================================================
INSERT INTO dn_mdm_golden_record (entity_id, biz_key, data_json, status, version, source_system, created_by)
SELECT e.id, '阿里巴巴（中国）有限公司',
  '{"cust_id":"C00009","cust_name":"阿里巴巴（中国）有限公司","credit_code":"913301000000000001","cust_type":"企业","reg_date":"1999-09-10"}',
  'draft', 1, 'OldCRM', 'system'
FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_golden_record g WHERE g.entity_id=e.id AND g.data_json LIKE '%C00009%');
