-- 统一审批中心: 单一审批记录表, 各业务流(主数据变更/数据模型变更/脚本上线)提交即建一条, 统一状态机 + Redis Streams 事件总线
CREATE TABLE IF NOT EXISTS dn_approval (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  flow_type     VARCHAR(32)  NOT NULL COMMENT '流类型: MDM_CHANGE/DATAMODEL_CHANGE/SCRIPT_CHANGE',
  biz_id        VARCHAR(64)  NOT NULL COMMENT '该流底层业务记录id(变更申请/模型变更/脚本变更)',
  title         VARCHAR(255)          COMMENT '人类可读摘要',
  submitter     VARCHAR(64)           COMMENT '提交人',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  reviewer      VARCHAR(64)           COMMENT '审批人(允许==提交人, 自审自批)',
  review_comment VARCHAR(500)         COMMENT '审批意见',
  payload_json  TEXT                  COMMENT '流相关数据, 供 apply 使用',
  created_at    DATETIME              COMMENT '提交时间',
  reviewed_at   DATETIME              COMMENT '审批时间',
  INDEX idx_ap_status (status),
  INDEX idx_ap_flow_biz (flow_type, biz_id),
  INDEX idx_ap_submitter (submitter),
  INDEX idx_ap_created (created_at)
) COMMENT='统一审批记录';
