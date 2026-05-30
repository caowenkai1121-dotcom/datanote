-- 数据同步 DS-M4：写入通道（JDBC 默认 / STREAM_LOAD 走 Doris 原生导入）
USE datanote;

ALTER TABLE dn_sync_job
  ADD COLUMN write_channel VARCHAR(16) DEFAULT 'JDBC' COMMENT '写入通道:JDBC/STREAM_LOAD(仅Doris/StarRocks目标)';
