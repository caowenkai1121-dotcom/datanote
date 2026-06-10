-- ============================================================
-- 74_mdm_attr_element_golden_history.sql
-- R126: ①MDM属性绑定数据元(打通数据标准) ②黄金记录变更历史快照
-- ============================================================

-- ① dn_mdm_attribute 增加绑定数据元编码(关联 dn_data_element.element_code)
ALTER TABLE dn_mdm_attribute
  ADD COLUMN element_code VARCHAR(64) DEFAULT NULL COMMENT '绑定数据元编码(dn_data_element.element_code,落标用)';

-- ② 黄金记录变更历史(每次 save/publish/deactivate/merge 写一条快照)
CREATE TABLE IF NOT EXISTS dn_mdm_golden_history (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  golden_id   BIGINT NOT NULL       COMMENT '黄金记录ID',
  entity_id   BIGINT NOT NULL       COMMENT '所属实体',
  version     INT                   COMMENT '快照时版本号',
  biz_key     VARCHAR(256)          COMMENT '快照时业务主键',
  status      VARCHAR(16)           COMMENT '快照时状态',
  data_json   TEXT                  COMMENT '快照全属性值JSON',
  change_type VARCHAR(16)           COMMENT 'create/update/publish/deactivate/merge',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_mdm_gh_golden (golden_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='黄金记录变更历史快照';
