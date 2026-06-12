-- ============================================================
-- 78_notification.sql  IV-1 第二步: 站内通知中心
-- 四埋点: 任务指派 / 发布审批结果 / 指标预警建单 / 评论@我
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  receiver VARCHAR(80) NOT NULL COMMENT '接收人用户名',
  type VARCHAR(32) NOT NULL COMMENT 'TASK_ASSIGN/RELEASE_RESULT/METRIC_ALERT/COMMENT_AT',
  title VARCHAR(300) NOT NULL COMMENT '通知文案',
  ref_route VARCHAR(64) DEFAULT NULL COMMENT '深链路由(project/metrics/governance...)',
  ref_id BIGINT DEFAULT NULL COMMENT '深链对象ID(projectId/issueId...)',
  ref_tab VARCHAR(32) DEFAULT NULL COMMENT '深链页签(task/release...)',
  read_at DATETIME DEFAULT NULL COMMENT '已读时间(NULL=未读)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_receiver_read (receiver, read_at)
) COMMENT='站内通知';
