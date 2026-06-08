-- R69: dn_ai_approval 增 executed_at, 支持 resume 按已批 args 重放(执行后置位, 防重复执行)
ALTER TABLE `dn_ai_approval`
  ADD COLUMN `executed_at` datetime NULL COMMENT '已批写操作的实际执行时间(NULL=已批未执行)' AFTER `decided_at`;
