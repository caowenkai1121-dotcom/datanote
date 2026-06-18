package com.datanote.domain.develop;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnScriptChangeMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptChange;
import com.datanote.domain.orchestration.ScheduleLifecycleService;
import com.datanote.domain.orchestration.ScheduleTargetType;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.notify.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 脚本上线审批服务 — 提交→审批→上线/下线, 禁自批, 审批通过才真正执行上下线。
 * 与数据模型(DataModelService)/主数据(MdmCore)的治理审批同款范式, 强化数据开发的上线管控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptApprovalService {

    private final DnScriptChangeMapper changeMapper;
    private final DnScriptMapper scriptMapper;
    private final ScheduleLifecycleService scheduleLifecycleService;
    private final NotificationService notificationService;

    /** 提交脚本上线/下线审批。校验状态、防空内容、防重复提交。 */
    @Transactional(rollbackFor = Exception.class)
    public DnScriptChange submit(Long scriptId, String changeType, String reason) {
        if (scriptId == null) throw new BusinessException("脚本 ID 不能为空");
        DnScript s = scriptMapper.selectById(scriptId);
        if (s == null) throw new BusinessException("脚本不存在");
        String type = "OFFLINE".equalsIgnoreCase(changeType) ? "OFFLINE" : "ONLINE";
        boolean online = "online".equalsIgnoreCase(s.getScheduleStatus());
        if ("ONLINE".equals(type)) {
            if (s.getContent() == null || s.getContent().trim().isEmpty()) throw new BusinessException("脚本内容为空, 无法提交上线");
            if (online) throw new BusinessException("脚本已上线, 无需重复提交");
        } else if (!online) {
            throw new BusinessException("脚本未上线, 无需下线审批");
        }
        Long pend = changeMapper.selectCount(new QueryWrapper<DnScriptChange>()
                .eq("script_id", scriptId).eq("status", "pending"));
        if (pend != null && pend > 0) throw new BusinessException("该脚本已有待审批工单, 请勿重复提交");

        DnScriptChange c = new DnScriptChange();
        c.setScriptId(scriptId);
        c.setChangeType(type);
        c.setPayloadJson(s.getContent());
        c.setReason(reason);
        c.setStatus("pending");
        c.setRequestedBy(CurrentUserUtil.currentUser());
        c.setCreatedAt(LocalDateTime.now());
        changeMapper.insert(c);
        return c;
    }

    /** 审批: target=approved/rejected。禁自批; 通过时执行实际上线/下线; 通知申请人。 */
    @Transactional(rollbackFor = Exception.class)
    public DnScriptChange review(Long changeId, String target, String comment) {
        if (changeId == null) throw new BusinessException("工单 ID 不能为空");
        DnScriptChange c = changeMapper.selectById(changeId);
        if (c == null) throw new BusinessException("工单不存在");
        if (!"pending".equals(c.getStatus())) throw new BusinessException("该工单已审批, 不能重复操作");
        String reviewer = CurrentUserUtil.currentUser();
        if (!"admin".equals(reviewer) && reviewer.equals(c.getRequestedBy())) {
            throw new BusinessException("不能审批自己提交的申请");
        }
        boolean approved = "approved".equalsIgnoreCase(target);
        // 驳回须有原因(便于申请人修正, 与前端必填一致)
        if (!approved && (comment == null || comment.trim().isEmpty())) {
            throw new BusinessException("驳回必须填写原因");
        }
        c.setReviewer(reviewer);
        c.setReviewComment(comment);
        c.setStatus(approved ? "approved" : "rejected");
        c.setDecidedAt(LocalDateTime.now());
        changeMapper.updateById(c);

        DnScript s = scriptMapper.selectById(c.getScriptId());
        if (approved && s != null) {
            // 漂移护栏: 上线审批通过时, 脚本现内容须与提交时快照一致, 否则"审批所见≠上线内容"
            if ("ONLINE".equals(c.getChangeType())
                    && !norm(s.getContent()).equals(norm(c.getPayloadJson()))) {
                throw new BusinessException("脚本内容在提交审批后已变更, 请撤回后重新提交(保证审批所见即上线内容)");
            }
            try {
                if ("OFFLINE".equals(c.getChangeType())) {
                    scheduleLifecycleService.offlineLocal(c.getScriptId(), ScheduleTargetType.SCRIPT);
                } else {
                    scheduleLifecycleService.onlineLocalAfterApproval(c.getScriptId(), ScheduleTargetType.SCRIPT);
                }
            } catch (Exception e) {
                throw new BusinessException("审批通过但执行" + c.getChangeType() + "失败: " + e.getMessage());
            }
        }
        try {
            if (c.getRequestedBy() != null && !c.getRequestedBy().trim().isEmpty()) {
                String verdict = approved ? "已通过并执行" : "已驳回";
                notificationService.notify(c.getRequestedBy().trim(), "SCRIPT_REVIEW",
                        "脚本" + c.getChangeType() + "审批" + verdict + ": "
                                + (s != null ? s.getScriptName() : "#" + c.getScriptId()) + " (审批人 " + reviewer + ")",
                        "develop", c.getScriptId(), null);
            }
        } catch (Exception e) {
            log.warn("脚本审批通知失败", e);
        }
        return c;
    }

    /** 内容归一(去首尾空白, null→空), 供审批漂移比对。 */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /** 撤回: 申请人(或 admin)撤回自己提交的待审批工单, 释放"重复提交"锁以便修正后重提。 */
    @Transactional(rollbackFor = Exception.class)
    public DnScriptChange withdraw(Long changeId) {
        if (changeId == null) throw new BusinessException("工单 ID 不能为空");
        DnScriptChange c = changeMapper.selectById(changeId);
        if (c == null) throw new BusinessException("工单不存在");
        if (!"pending".equals(c.getStatus())) throw new BusinessException("仅待审批工单可撤回");
        String user = CurrentUserUtil.currentUser();
        if (!"admin".equals(user) && !user.equals(c.getRequestedBy())) {
            throw new BusinessException("只能撤回自己提交的申请");
        }
        c.setStatus("withdrawn");
        c.setDecidedAt(LocalDateTime.now());
        changeMapper.updateById(c);
        return c;
    }

    /** 变更工单列表(可按状态过滤), 批量回填脚本名。 */
    public List<DnScriptChange> listChanges(String status) {
        QueryWrapper<DnScriptChange> qw = new QueryWrapper<>();
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("created_at");
        List<DnScriptChange> list = changeMapper.selectList(qw);
        if (list == null || list.isEmpty()) return list == null ? new java.util.ArrayList<>() : list;
        List<Long> ids = list.stream().map(DnScriptChange::getScriptId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, String> nameMap = new HashMap<>();
        if (!ids.isEmpty()) { List<DnScript> _ss = scriptMapper.selectBatchIds(ids); if (_ss != null) for (DnScript s : _ss) nameMap.put(s.getId(), s.getScriptName()); } // selectList 理论可返回 null
        for (DnScriptChange c : list) c.setScriptName(nameMap.get(c.getScriptId()));
        return list;
    }

    /** 待审批工单数(供菜单角标)。 */
    public long pendingCount() {
        Long n = changeMapper.selectCount(new QueryWrapper<DnScriptChange>().eq("status", "pending"));
        return n == null ? 0 : n;
    }

    /** 某脚本是否有待审批工单(供前端禁用重复提交按钮)。 */
    public boolean hasPending(Long scriptId) {
        if (scriptId == null) return false;
        Long n = changeMapper.selectCount(new QueryWrapper<DnScriptChange>()
                .eq("script_id", scriptId).eq("status", "pending"));
        return n != null && n > 0;
    }

    /** 任务三态: DRAFT(未提交,可随时改) / PENDING(已提交待审) / ONLINE(已上线)。 */
    public String stateOf(Long scriptId) {
        DnScript s = scriptMapper.selectById(scriptId);
        if (s == null) return "DRAFT";
        if ("online".equalsIgnoreCase(s.getScheduleStatus())) return "ONLINE";
        if (hasPending(scriptId)) return "PENDING";
        return "DRAFT";
    }

    /** 点"编辑": 已提交/已上线 退回未提交(草稿)以便修改(改完需重新提交)。撤回待审工单 + 已上线则下线。 */
    @Transactional(rollbackFor = Exception.class)
    public void revertToDraft(Long scriptId) {
        if (scriptId == null) throw new BusinessException("脚本 ID 不能为空");
        DnScriptChange pend = pendingChange(scriptId);
        if (pend != null) {   // 撤回待审工单
            pend.setStatus("withdrawn");
            pend.setDecidedAt(LocalDateTime.now());
            changeMapper.updateById(pend);
        }
        DnScript s = scriptMapper.selectById(scriptId);
        if (s != null && "online".equalsIgnoreCase(s.getScheduleStatus())) {   // 已上线→下线
            try {
                scheduleLifecycleService.offlineLocal(scriptId, ScheduleTargetType.SCRIPT);
            } catch (Exception e) {
                throw new BusinessException("退回草稿(下线)失败: " + e.getMessage());
            }
        }
    }

    /** 某脚本当前待审批工单(供申请人撤回, develop:edit 可达); 无则返回 null。 */
    public DnScriptChange pendingChange(Long scriptId) {
        if (scriptId == null) return null;
        return changeMapper.selectOne(new QueryWrapper<DnScriptChange>()
                .eq("script_id", scriptId).eq("status", "pending")
                .orderByDesc("created_at").last("LIMIT 1"));
    }
}
