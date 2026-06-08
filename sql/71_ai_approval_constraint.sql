-- 71: dn_ai_approval 审批留痕约束(AI Agent 写操作审批门)
-- 幂等键: 同会话同步同工具只一条审批记录(防重放重复挂起)
ALTER TABLE dn_ai_approval ADD UNIQUE KEY uk_session_step_skill (session_id, step_seq, skill_name);
-- 审批人/时间非空 CHECK: 非 pending 态必须有 decided_by + decided_at(MySQL8 强制)
ALTER TABLE dn_ai_approval ADD CONSTRAINT chk_ai_approval_decided
    CHECK (status = 'pending' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL));
