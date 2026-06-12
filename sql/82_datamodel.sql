-- ============================================================
--  数据模型(三层建模: 业务/逻辑/物理 + L1-L5 主题域分层 + 数仓分层 + 申请审批流转)
--  幂等: 重复执行安全(IF NOT EXISTS / information_schema 判断)
-- ============================================================

-- ---------- 主题域 L1-L5 + 数仓分层增强(dn_subject) ----------
-- level: 1-5 树深度(L1 分组/L2 主题域/L3 业务对象...); layer_type: 数仓物理分层
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dn_subject' AND COLUMN_NAME='level');
SET @s1 := IF(@c1=0, 'ALTER TABLE dn_subject ADD COLUMN level INT NOT NULL DEFAULT 1 COMMENT ''L1-L5 树深度''', 'SELECT 1');
PREPARE st FROM @s1; EXECUTE st; DEALLOCATE PREPARE st;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dn_subject' AND COLUMN_NAME='layer_type');
SET @s2 := IF(@c2=0, 'ALTER TABLE dn_subject ADD COLUMN layer_type VARCHAR(16) NULL COMMENT ''数仓分层 ODS/DWD/DIM/DWS/ADS''', 'SELECT 1');
PREPARE st FROM @s2; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 模型(业务/逻辑/物理) ----------
CREATE TABLE IF NOT EXISTS dn_model (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  model_code    VARCHAR(64)  NOT NULL COMMENT '模型编码(唯一)',
  model_name    VARCHAR(128) NOT NULL COMMENT '模型名称',
  model_type    VARCHAR(8)   NOT NULL COMMENT 'BIZ业务/LOGIC逻辑/PHYS物理',
  subject_id    BIGINT       NULL     COMMENT '所属主题域 dn_subject.id',
  source_model_id BIGINT     NULL     COMMENT '溯源模型(逻辑←业务, 物理←逻辑)',
  dw_layer      VARCHAR(16)  NULL     COMMENT '数仓分层 ODS/DWD/DIM/DWS/ADS',
  version       INT          NOT NULL DEFAULT 1 COMMENT '版本号',
  status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/PUBLISHED/REJECTED/ARCHIVED',
  owner         VARCHAR(64)  NULL     COMMENT '负责人',
  description   VARCHAR(512) NULL     COMMENT '说明',
  created_by    VARCHAR(64)  NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_code (model_code),
  KEY idx_model_type (model_type),
  KEY idx_model_subject (subject_id),
  KEY idx_model_source (source_model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据模型(三层)';

-- ---------- 模型实体(业务对象 L3 / 逻辑实体 L4) ----------
CREATE TABLE IF NOT EXISTS dn_model_entity (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  model_id        BIGINT       NOT NULL COMMENT '所属模型',
  entity_code     VARCHAR(64)  NOT NULL COMMENT '实体编码',
  entity_name     VARCHAR(128) NOT NULL COMMENT '实体名称',
  level           INT          NOT NULL DEFAULT 4 COMMENT 'L3业务对象/L4逻辑实体',
  parent_entity_id BIGINT      NULL     COMMENT '父实体(实体层级)',
  source_entity_id BIGINT      NULL     COMMENT '溯源实体(逻辑实体←业务对象)',
  physical_table  VARCHAR(128) NULL     COMMENT '物理模型: 映射物理表名',
  biz_definition  VARCHAR(512) NULL     COMMENT '业务定义',
  sort_order      INT          NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_entity_model (model_id),
  KEY idx_entity_parent (parent_entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型实体';

-- ---------- 实体属性(L5 字段, 绑数据标准) ----------
CREATE TABLE IF NOT EXISTS dn_model_attribute (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  entity_id     BIGINT       NOT NULL COMMENT '所属实体',
  attr_code     VARCHAR(64)  NOT NULL COMMENT '属性编码',
  attr_name     VARCHAR(128) NOT NULL COMMENT '属性名称',
  data_type     VARCHAR(32)  NULL     COMMENT '数据类型 STRING/INT/DECIMAL/DATE/...',
  data_length   VARCHAR(32)  NULL     COMMENT '长度/精度 如 64 或 18,2',
  is_pk         TINYINT      NOT NULL DEFAULT 0 COMMENT '主键',
  is_nullable   TINYINT      NOT NULL DEFAULT 1 COMMENT '可空',
  default_value VARCHAR(128) NULL,
  element_code  VARCHAR(64)  NULL     COMMENT '绑数据标准 dn_data_element.element_code',
  dict_code     VARCHAR(64)  NULL     COMMENT '绑码表 dn_code_dict.dict_code',
  ref_entity_id BIGINT       NULL     COMMENT '外键引用实体',
  physical_column VARCHAR(128) NULL   COMMENT '物理模型: 映射物理列名',
  biz_definition VARCHAR(512) NULL    COMMENT '业务含义',
  sort_order    INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_attr_entity (entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实体属性';

-- ---------- 实体关系 ----------
CREATE TABLE IF NOT EXISTS dn_model_relation (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  model_id         BIGINT      NOT NULL,
  source_entity_id BIGINT      NOT NULL,
  target_entity_id BIGINT      NOT NULL,
  relation_type    VARCHAR(8)  NOT NULL DEFAULT '1:N' COMMENT '1:1 / 1:N / M:N',
  fk_attr_id       BIGINT      NULL     COMMENT '外键属性',
  description      VARCHAR(256) NULL,
  created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_rel_model (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实体关系';

-- ---------- 模型变更工单(申请/审批流转) ----------
CREATE TABLE IF NOT EXISTS dn_model_change (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  model_id      BIGINT       NOT NULL COMMENT '目标模型',
  change_type   VARCHAR(16)  NOT NULL COMMENT 'CREATE/UPDATE/PUBLISH/ARCHIVE',
  payload_json  MEDIUMTEXT   NULL     COMMENT '变更快照',
  reason        VARCHAR(512) NULL     COMMENT '申请说明',
  status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  requested_by  VARCHAR(64)  NULL,
  reviewer      VARCHAR(64)  NULL,
  review_comment VARCHAR(512) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at    DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_change_model (model_id),
  KEY idx_change_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型变更工单';
