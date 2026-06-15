-- R126: 指标加目标值(供详情驾驶舱 当前/目标/达成率)
ALTER TABLE dn_metric ADD COLUMN target_value DECIMAL(20,4) NULL COMMENT '目标值' AFTER unit;
