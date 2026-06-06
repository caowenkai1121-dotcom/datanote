-- ============================================================
-- 63_mdm_pubsub.sql  主数据发布订阅(Pub/Sub)
-- 订阅方系统订阅某实体的黄金记录变更，变更发生时向 endpoint 推送并记日志
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_mdm_subscription (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  subscriber_system VARCHAR(128) NOT NULL COMMENT '订阅方系统',
  entity_id         BIGINT NOT NULL       COMMENT '订阅的实体',
  change_types      VARCHAR(64)           COMMENT '订阅的变更类型(逗号分隔: create,update,delete)',
  endpoint          VARCHAR(512)          COMMENT '推送地址',
  status            TINYINT DEFAULT 1     COMMENT '1启用/0停用',
  description       VARCHAR(512)          COMMENT '描述',
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mdm_sub_entity (entity_id),
  KEY idx_mdm_sub_status (status)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据发布订阅';

CREATE TABLE IF NOT EXISTS dn_mdm_publish_log (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  subscription_id  BIGINT NOT NULL       COMMENT '关联订阅',
  golden_record_id BIGINT NOT NULL       COMMENT '黄金记录',
  change_type      VARCHAR(16)           COMMENT 'create/update/delete',
  biz_key          VARCHAR(256)          COMMENT '业务主键(展示用)',
  status           VARCHAR(16) DEFAULT 'success' COMMENT 'success成功/failed失败',
  message          VARCHAR(512)          COMMENT '推送结果说明',
  published_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_mdm_pub_sub (subscription_id),
  KEY idx_mdm_pub_golden (golden_record_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='主数据发布日志';

-- ---------------- 种子示例(客户实体的1条订阅) ----------------
INSERT INTO dn_mdm_subscription (subscriber_system, entity_id, change_types, endpoint, status, description)
SELECT '下游数据仓库(DW)', e.id, 'create,update,delete', 'http://dw.internal/api/mdm/customer/sync', 1,
  '数仓订阅客户主数据全量变更，用于维度表同步'
FROM dn_mdm_entity e JOIN dn_mdm_domain d ON e.domain_id=d.id
WHERE d.domain_code='CUSTOMER' AND e.entity_code='customer'
  AND NOT EXISTS (SELECT 1 FROM dn_mdm_subscription s WHERE s.entity_id=e.id AND s.subscriber_system='下游数据仓库(DW)');
