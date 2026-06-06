-- ============================================================
-- 65_mdm_surv_test_seed.sql  存活性合并集成 演示种子
-- 重激活 R67 已停用的 C00009 阿里巴巴重复记录(reg_date 1999-09-10, 比 C00001 的 1999-09-09 更新)
-- 使其与 C00001 在统一社会信用代码上成重复簇, 合并时可演示 reg_date=latest 存活策略生效
-- ============================================================
UPDATE dn_mdm_golden_record
SET status='draft', updated_at=NOW()
WHERE data_json LIKE '%C00009%' AND status='inactive';
