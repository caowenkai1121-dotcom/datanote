package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiApprovalMapper;
import com.datanote.platform.ai.agent.model.DnAiApproval;
import com.datanote.platform.ai.agent.tool.AiTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审批门状态机(dn_ai_approval 持久)。幂等键 session+skill+args(resume 重入按 args 稳定命中, 不依赖运行 seq)。
 * HIGH 每次强审批; MEDIUM 会话级同 skill 已批则豁免。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalGate {

    private final DnAiApprovalMapper approvalMapper;

    public enum Outcome { APPROVED, REJECTED, PENDING }

    /** 检查/挂起审批。stepSeq 仅用于首次 insert 留痕(查询按 args 稳定匹配)。 */
    public Outcome check(String sessionId, int stepSeq, AiTool tool, String argsJson, boolean isHigh) {
        return check(sessionId, stepSeq, tool, argsJson, isHigh, false);
    }

    /**
     * autoApprove=true(本任务批量自动批准): 待审/新建一律置 approved 放行, 已 rejected 仍尊重拒绝。
     * 仅短路本审批门; 调用方的 PermGate(功能权限)/DataAcl(数据权限) 仍逐个拦截, 不被绕过。
     */
    public Outcome check(String sessionId, int stepSeq, AiTool tool, String argsJson, boolean isHigh, boolean autoApprove) {
        String args = (argsJson == null || argsJson.isEmpty()) ? "{}" : argsJson;
        if (args.length() > 8000) args = args.substring(0, 8000);
        DnAiApproval exist = findOne(sessionId, tool.name(), args);
        if (exist != null) {
            if ("approved".equals(exist.getStatus())) return Outcome.APPROVED;
            if ("rejected".equals(exist.getStatus())) return Outcome.REJECTED;
            // pending: 批量模式直接批准放行
            if (autoApprove) {
                approvalMapper.update(null, new UpdateWrapper<DnAiApproval>()
                        .eq("id", exist.getId()).eq("status", "pending")
                        .set("status", "approved").set("decided_by", "auto").set("decided_at", LocalDateTime.now()));
                return Outcome.APPROVED;
            }
            return Outcome.PENDING;
        }
        // 安全: 审批严格绑定 args(session+skill+完全相同 args 命中 approved 才放行)。
        // 不做"同会话同 skill 任意 args 豁免"——否则一次良性审批可放行同会话该工具的任意后续写入(审批绕过)。
        // HIGH/MEDIUM 一视同仁按 args 精确审批; isHigh 仅保留语义入参, 不再放宽 MEDIUM。
        DnAiApproval a = new DnAiApproval();
        a.setSessionId(sessionId);
        a.setStepSeq(stepSeq);
        a.setSkillName(tool.name());
        a.setArgsJson(args);
        a.setRiskLevel(tool.risk() == null ? "HIGH" : tool.risk().name());
        a.setStatus(autoApprove ? "approved" : "pending");
        if (autoApprove) { a.setDecidedBy("auto"); a.setDecidedAt(LocalDateTime.now()); }
        a.setCreatedAt(LocalDateTime.now());
        try {
            approvalMapper.insert(a);
        } catch (Exception e) {
            // 唯一键竞态: 重查, 已批则放行
            DnAiApproval r = findOne(sessionId, tool.name(), args);
            if (r != null && "approved".equals(r.getStatus())) return Outcome.APPROVED;
            if (r != null && "rejected".equals(r.getStatus())) return Outcome.REJECTED;
        }
        return autoApprove ? Outcome.APPROVED : Outcome.PENDING;
    }

    /** 批量任务收尾: 本会话所有已批未执行的审批标记为已执行, 防 resume 重放与 run 内联执行双跑(at-most-once)。 */
    public void markSessionExecuted(String sessionId) {
        approvalMapper.update(null, new UpdateWrapper<DnAiApproval>()
                .eq("session_id", sessionId).eq("status", "approved").isNull("executed_at")
                .set("executed_at", LocalDateTime.now()));
    }

    private DnAiApproval findOne(String sessionId, String skill, String args) {
        return approvalMapper.selectOne(new QueryWrapper<DnAiApproval>()
                .eq("session_id", sessionId).eq("skill_name", skill).eq("args_json", args).last("LIMIT 1"));
    }
}
