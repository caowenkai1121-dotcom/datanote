-- 数据开发脚本上线审批: 上线由直接置 online 改为提交→审批→上线(对齐数据模型/主数据治理范式)。
CREATE TABLE IF NOT EXISTS dn_script_change (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  script_id      BIGINT       NOT NULL COMMENT '目标脚本',
  change_type    VARCHAR(16)  NOT NULL COMMENT 'ONLINE/OFFLINE',
  payload_json   MEDIUMTEXT   NULL     COMMENT '提交时脚本内容快照',
  reason         VARCHAR(512) NULL     COMMENT '申请说明',
  status         VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  requested_by   VARCHAR(64)  NULL,
  reviewer       VARCHAR(64)  NULL,
  review_comment VARCHAR(512) NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at     DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_script_change_script (script_id),
  KEY idx_script_change_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脚本上线变更工单';
