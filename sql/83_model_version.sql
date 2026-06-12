-- 模型版本历史: 每次审批发布产生一份模型快照, 可追溯/对比。
CREATE TABLE IF NOT EXISTS dn_model_version (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  model_id       BIGINT       NOT NULL COMMENT '所属模型',
  version        INT          NOT NULL COMMENT '版本号',
  snapshot_json  MEDIUMTEXT   NULL     COMMENT '该版本模型完整快照(含实体/属性/关系)',
  change_summary VARCHAR(512) NULL     COMMENT '变更摘要(申请说明)',
  published_by   VARCHAR(64)  NULL     COMMENT '发布人(审批人)',
  published_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ver_model (model_id),
  KEY idx_ver_mv (model_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型版本历史';
