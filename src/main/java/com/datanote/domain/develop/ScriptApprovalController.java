package com.datanote.domain.develop;

import com.datanote.common.model.R;
import com.datanote.domain.develop.model.DnScriptChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 脚本上线审批 Controller — 提交上线/下线审批、审批工单列表、审批操作。
 */
@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
@Tag(name = "脚本上线审批", description = "数据开发脚本上线/下线的提交-审批-执行流转")
public class ScriptApprovalController {

    private final ScriptApprovalService approvalService;

    @Operation(summary = "提交脚本上线/下线审批")
    @PostMapping("/{id}/submit-change")
    public R<DnScriptChange> submit(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String changeType = body != null ? body.getOrDefault("changeType", "ONLINE") : "ONLINE";
        String reason = body != null ? body.get("reason") : null;
        return R.ok(approvalService.submit(id, changeType, reason));
    }

    @Operation(summary = "脚本审批工单列表")
    @GetMapping("/changes")
    public R<List<DnScriptChange>> changes(@RequestParam(required = false) String status) {
        return R.ok(approvalService.listChanges(status));
    }

    @Operation(summary = "待审批工单数")
    @GetMapping("/changes/pending-count")
    public R<Long> pendingCount() {
        return R.ok(approvalService.pendingCount());
    }

    @Operation(summary = "某脚本是否有待审批工单")
    @GetMapping("/{id}/has-pending")
    public R<Boolean> hasPending(@PathVariable Long id) {
        return R.ok(approvalService.hasPending(id));
    }

    @Operation(summary = "某脚本当前待审批工单(供申请人撤回)")
    @GetMapping("/{id}/pending-change")
    public R<DnScriptChange> pendingChange(@PathVariable Long id) {
        return R.ok(approvalService.pendingChange(id));
    }

    @Operation(summary = "撤回自己提交的待审批工单")
    @PostMapping("/withdraw-change/{changeId}")
    public R<DnScriptChange> withdraw(@PathVariable Long changeId) {
        return R.ok(approvalService.withdraw(changeId));
    }

    @Operation(summary = "脚本三态(DRAFT/PENDING/ONLINE)")
    @GetMapping("/{id}/state")
    public R<String> state(@PathVariable Long id) {
        return R.ok(approvalService.stateOf(id));
    }

    @Operation(summary = "点编辑: 已提交/已上线 退回未提交(草稿)")
    @PostMapping("/{id}/revert-to-draft")
    public R<String> revertToDraft(@PathVariable Long id) {
        approvalService.revertToDraft(id);
        return R.ok("已退回未提交, 可编辑(改完需重新提交)");
    }

    @Operation(summary = "审批脚本上线工单(approved/rejected)")
    @PostMapping("/changes/{changeId}/review")
    public R<DnScriptChange> review(@PathVariable Long changeId, @RequestBody Map<String, String> body) {
        String target = body == null ? null : body.get("target");
        String comment = body == null ? null : body.get("comment");
        return R.ok(approvalService.review(changeId, target, comment));
    }
}
