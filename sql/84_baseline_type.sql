-- 基线类型(看板/核心): 创建表单已收集 baselineType、列表已按类型渲染,
-- 但 dn_baseline 缺该列致始终存不进、列表永远显示"核心"。补列做实该分类功能。
ALTER TABLE dn_baseline ADD COLUMN baseline_type VARCHAR(20) DEFAULT 'core' COMMENT '基线类型: core=核心 / dashboard=看板';
