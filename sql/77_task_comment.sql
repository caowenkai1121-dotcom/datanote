-- ============================================================
-- 77_task_comment.sql  IV-1: 任务评论(协作触达第一步)
-- ============================================================

CREATE TABLE IF NOT EXISTS dn_project_task_comment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL COMMENT '任务ID',
  author VARCHAR(80) NOT NULL COMMENT '评论人(服务端写入)',
  content VARCHAR(1000) NOT NULL COMMENT '评论内容',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task (task_id)
) COMMENT='项目任务评论';
