-- 数据治理 M12：全局审计中心（幂等：CREATE TABLE IF NOT EXISTS；只增不改，无 update 列/触发器）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_audit_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_name   VARCHAR(80)  NOT NULL DEFAULT 'anonymous' COMMENT '操作人(匿名记 anonymous)',
  action_type VARCHAR(20)  NOT NULL COMMENT 'LOGIN/DATA_ACCESS/EXPORT/PERM_CHANGE/META_CHANGE/RULE_CHANGE/LABEL_CHANGE/OTHER',
  target      VARCHAR(255) DEFAULT NULL COMMENT '操作对象(预留)',
  method      VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法',
  path        VARCHAR(255) DEFAULT NULL COMMENT '请求路径',
  ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
  status      INT          DEFAULT NULL COMMENT '响应状态码',
  detail      TEXT         DEFAULT NULL COMMENT '详情(耗时/查询串等)',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  INDEX idx_created_at (created_at),
  INDEX idx_user_name (user_name),
  INDEX idx_action_type (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局审计日志(只增不改)';
