-- ============================================================
-- 58_mdm_xref.sql  主数据交叉引用(XREF)
-- 黄金记录(MDM ID) ↔ 各源系统业务ID 的映射，支持按源ID反查黄金记录
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_xref (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  golden_record_id BIGINT NOT NULL      COMMENT '关联黄金记录(MDM全局ID)',
  entity_id        BIGINT               COMMENT '冗余:所属实体',
  source_system    VARCHAR(64) NOT NULL COMMENT '源系统',
  source_id        VARCHAR(128) NOT NULL COMMENT '源系统业务ID',
  match_score      DECIMAL(5,2)         COMMENT '匹配置信度',
  is_primary       TINYINT DEFAULT 0    COMMENT '是否主源',
  created_by       VARCHAR(50),
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mdm_xref_src (source_system, source_id),
  KEY idx_mdm_xref_golden (golden_record_id),
  KEY idx_mdm_xref_entity (entity_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据交叉引用';

-- ---------------- 种子(客户黄金记录的源系统映射) ----------------
INSERT INTO dn_mdm_xref (golden_record_id, entity_id, source_system, source_id, match_score, is_primary, created_by)
SELECT g.id, g.entity_id, 'CRM', 'CRM-1001', 100.00, 1, 'system'
FROM dn_mdm_golden_record g
WHERE g.data_json LIKE '%C00001%' AND g.status='active'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_xref x WHERE x.source_system='CRM' AND x.source_id='CRM-1001');

INSERT INTO dn_mdm_xref (golden_record_id, entity_id, source_system, source_id, match_score, is_primary, created_by)
SELECT g.id, g.entity_id, 'ERP', 'ERP-A0001', 96.50, 0, 'system'
FROM dn_mdm_golden_record g
WHERE g.data_json LIKE '%C00001%' AND g.status='active'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_xref x WHERE x.source_system='ERP' AND x.source_id='ERP-A0001');

INSERT INTO dn_mdm_xref (golden_record_id, entity_id, source_system, source_id, match_score, is_primary, created_by)
SELECT g.id, g.entity_id, 'ERP', 'ERP-2002', 100.00, 1, 'system'
FROM dn_mdm_golden_record g
WHERE g.data_json LIKE '%C00002%'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_xref x WHERE x.source_system='ERP' AND x.source_id='ERP-2002');
