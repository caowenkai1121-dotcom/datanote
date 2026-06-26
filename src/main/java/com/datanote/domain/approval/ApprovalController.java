package com.datanote.domain.approval;

import com.datanote.common.model.R;
import com.datanote.domain.approval.model.DnApproval;
import com.datanote.platform.iam.CurrentUserUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 统一审批中心 API。审批权限由 PermInterceptor 把关; 允许自审自批。 */
@Tag(name = "统一审批中心")
@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;

    @Operation(summary = "待审批列表")
    @GetMapping("/pending")
    public R<List<DnApproval>> pending() {
        return R.ok(service.listPending());
    }

    @Operation(summary = "审批列表(可按状态/流类型筛选)")
    @GetMapping("/list")
    public R<List<DnApproval>> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String flowType) {
        return R.ok(service.list(status, flowType));
    }

    @Operation(summary = "审批详情")
    @GetMapping("/{id}")
    public R<DnApproval> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @Operation(summary = "审批: 通过/驳回(允许自审自批)")
    @PostMapping("/{id}/review")
    public R<DnApproval> review(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object dec = body == null ? null : body.get("approve");
        boolean approve = Boolean.TRUE.equals(dec) || "true".equalsIgnoreCase(String.valueOf(dec))
                || "APPROVED".equalsIgnoreCase(String.valueOf(body == null ? null : body.get("decision")));
        String comment = body == null || body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        try {
            return R.ok(service.review(id, approve, CurrentUserUtil.currentUser(), comment));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }
}
