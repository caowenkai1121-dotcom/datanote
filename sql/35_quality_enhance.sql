-- 数据治理 M5：质量规则阈值/强弱/维度扩列（幂等，按 information_schema 守护）
USE datanote;

-- dn_quality_rule 扩列（仅 pass_threshold 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_quality_rule' AND COLUMN_NAME = 'pass_threshold');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_quality_rule
     ADD COLUMN pass_threshold DECIMAL(5,2) DEFAULT 100 COMMENT ''通过率阈值(%)，实际通过率<它判 failed'',
     ADD COLUMN block_downstream TINYINT DEFAULT 0 COMMENT ''强规则:1 失败阻塞下游 0 弱规则'',
     ADD COLUMN dimension VARCHAR(20) DEFAULT NULL COMMENT ''质量维度 完整性/准确性/一致性/唯一性/及时性/有效性''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
