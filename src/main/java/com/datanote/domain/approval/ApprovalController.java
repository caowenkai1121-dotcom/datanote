package com.datanote.domain.approval;

import com.datanote.common.model.R;
import com.datanote.domain.approval.handler.DataModelApprovalHandler;
import com.datanote.domain.approval.handler.MdmApprovalHandler;
import com.datanote.domain.approval.handler.ScriptApprovalHandler;
import com.datanote.domain.approval.model.DnApproval;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.iam.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 统一审批中心 API。审批中心绕过各流 PermInterceptor(handler 直调), 故此处按流类型自行校验对应审批权限; 允许自审自批。 */
@Tag(name = "统一审批中心")
@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;
    private final RbacService rbacService;

    // 流类型 → 对应审批权限(与各流直连端点的 WRITE_RULES 一致)
    private static final Map<String, String> FLOW_PERM = new HashMap<>();
    static {
        FLOW_PERM.put(MdmApprovalHandler.FLOW, "mdm:approve");
        FLOW_PERM.put(DataModelApprovalHandler.FLOW, "datamodel:approve");
        FLOW_PERM.put(ScriptApprovalHandler.FLOW, "develop:approve");
    }

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
        // 按流类型校验对应审批权限(handler 直调各流方法会绕过其 PermInterceptor, 故此处把关)
        DnApproval a = service.get(id);
        if (a == null) return R.fail("审批记录不存在");
        String need = FLOW_PERM.get(a.getFlowType());
        String user = CurrentUserUtil.currentUser();
        if (need != null && !RbacService.hasPermission(rbacService.getUserPermsByUsername(user), need)) {
            return R.fail("无该类型审批权限(" + need + ")");
        }
        try {
            return R.ok(service.review(id, approve, user, comment));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }
}
