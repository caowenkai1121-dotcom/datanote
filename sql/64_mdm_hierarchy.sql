-- ============================================================
-- 64_mdm_hierarchy.sql  主数据层级管理(Hierarchy)
-- 黄金记录间的树形层级(组织架构/地区/产品分类)
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_hierarchy (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_id        BIGINT NOT NULL       COMMENT '所属实体',
  hierarchy_type   VARCHAR(64) NOT NULL  COMMENT '层级类型(org组织/region地区/category产品分类...)',
  parent_record_id BIGINT                COMMENT '父黄金记录(根节点可空)',
  child_record_id  BIGINT NOT NULL       COMMENT '子黄金记录',
  sort_order       INT DEFAULT 0         COMMENT '同级排序',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_hier_entity (entity_id),
  KEY idx_mdm_hier_parent (parent_record_id),
  KEY idx_mdm_hier_type (entity_id, hierarchy_type)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据层级关系';

-- ---------------- 种子示例(客户实体的组织层级: 阿里巴巴为父, 腾讯为子) ----------------
INSERT INTO dn_mdm_hierarchy (entity_id, hierarchy_type, parent_record_id, child_record_id, sort_order)
SELECT p.entity_id, 'org', p.id, ch.id, 1
FROM dn_mdm_golden_record p
JOIN dn_mdm_golden_record ch ON ch.entity_id = p.entity_id
WHERE p.biz_key = '阿里巴巴(中国)有限公司' AND ch.biz_key = '腾讯科技(深圳)有限公司'
  AND NOT EXISTS (
    SELECT 1 FROM dn_mdm_hierarchy h
    WHERE h.entity_id = p.entity_id AND h.hierarchy_type = 'org' AND h.child_record_id = ch.id
  );
