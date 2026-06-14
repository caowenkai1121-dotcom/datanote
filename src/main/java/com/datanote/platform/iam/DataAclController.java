package com.datanote.platform.iam;

import com.datanote.common.model.R;
import com.datanote.platform.iam.model.DnDataGrant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据权限授权管理 — 设/查某资源(库表/项目/模型...)的可见范围。
 * 全部端点由 PermInterceptor 在 /api/data-acl 上要求 data:grant。
 */
@RestController
@RequestMapping("/api/data-acl")
@RequiredArgsConstructor
@Tag(name = "数据权限", description = "资源级访问控制: 把资源授权给角色/用户")
public class DataAclController {

    private final DataAclService dataAclService;

    @Operation(summary = "查某资源的授权清单")
    @GetMapping("/grants")
    public R<List<DnDataGrant>> grants(@RequestParam String resourceType, @RequestParam String resourceId) {
        return R.ok(dataAclService.listGrants(resourceType, resourceId));
    }

    @Operation(summary = "覆盖式设置某资源授权(空=取消受限恢复公开)")
    @PostMapping("/grants")
    public R<String> setGrants(@RequestBody Map<String, Object> body) {
        String type = str(body.get("resourceType"));
        String id = str(body.get("resourceId"));
        if (type == null || id == null) return R.fail("resourceType/resourceId 不能为空");
        List<DnDataGrant> grants = new ArrayList<>();
        Object arr = body.get("grants");
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                DnDataGrant g = new DnDataGrant();
                g.setPrincipalType(str(m.get("principalType")));
                g.setPrincipal(str(m.get("principal")));
                grants.add(g);
            }
        }
        dataAclService.setGrants(type, id, grants, CurrentUserUtil.currentUser());
        return R.ok(grants.isEmpty() ? "已取消受限(恢复公开)" : "已设置 " + grants.size() + " 条授权");
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
