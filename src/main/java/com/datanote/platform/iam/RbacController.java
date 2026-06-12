package com.datanote.platform.iam;

import com.datanote.platform.iam.model.DnRole;
import com.datanote.platform.iam.model.DnUser;
import com.datanote.common.model.R;
import com.datanote.platform.iam.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 管理控制器 — 用户/角色 CRUD、角色分配、当前用户查询。
 */
@Tag(name = "RBAC 权限管理")
@RestController
@RequestMapping("/api/rbac")
@RequiredArgsConstructor
public class RbacController {

    private final RbacService rbacService;

    // ---------------- 当前用户 ----------------

    /**
     * 查询当前登录用户及其权限集（未登录返回 authenticated=false）。
     */
    @Operation(summary = "当前用户与权限")
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Map<String, Object> data = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        data.put("authenticated", authenticated);
        if (authenticated) {
            String username = auth.getName();
            data.put("username", username);
            data.put("perms", resolvePerms(auth));
        }
        return R.ok(data);
    }

    /**
     * 解析权限集：优先查 dn_user；查不到 / 表不存在异常时按 authorities 判断 admin（内存兜底），不抛 500。
     */
    private Set<String> resolvePerms(Authentication auth) {
        Set<String> perms;
        try {
            perms = rbacService.getUserPermsByUsername(auth.getName());
        } catch (Exception e) {
            perms = new java.util.HashSet<>();
        }
        if (perms.isEmpty()) {
            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> "ROLE_ADMIN".equals(a) || "*".equals(a));
            if (isAdmin) {
                perms = new java.util.HashSet<>();
                perms.add("*");
            }
        }
        return perms;
    }

    // ---------------- 用户 ----------------

    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public R<List<Map<String, Object>>> listUsers() {
        List<DnUser> users = rbacService.listUsers();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DnUser u : users) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("status", u.getStatus());
            m.put("createdAt", u.getCreatedAt());
            m.put("roleIds", rbacService.getUserRoleIds(u.getId()));
            result.add(m);
        }
        return R.ok(result);
    }

    @Operation(summary = "创建用户")
    @PostMapping("/users")
    public R<DnUser> createUser(@RequestBody DnUser user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "用户名不能为空");
        }
        if (rbacService.findByUsername(user.getUsername()) != null) {
            return R.fail(R.CODE_BAD_REQUEST, "用户名已存在");
        }
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "密码不能为空");
        }
        return R.ok(rbacService.createUser(user));
    }

    @Operation(summary = "更新用户")
    @PutMapping("/users/{id}")
    public R<DnUser> updateUser(@PathVariable Long id, @RequestBody DnUser user) {
        user.setId(id);
        user.setUsername(null); // 用户名不可改
        return R.ok(rbacService.updateUser(user));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/users/{id}")
    public R<String> deleteUser(@PathVariable Long id) {
        rbacService.deleteUser(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "给用户分配角色")
    @PostMapping("/users/{id}/roles")
    public R<String> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest req) {
        rbacService.assignRoles(id, req.getRoleIds());
        return R.ok("分配成功");
    }

    // ---------------- 角色 ----------------

    @Operation(summary = "角色列表")
    @GetMapping("/roles")
    public R<List<Map<String, Object>>> listRoles() {
        List<DnRole> roles = rbacService.listRoles();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DnRole r : roles) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("roleCode", r.getRoleCode());
            m.put("roleName", r.getRoleName());
            m.put("description", r.getDescription());
            m.put("perms", rbacService.listRolePerms(r.getId()));
            result.add(m);
        }
        return R.ok(result);
    }

    @Operation(summary = "创建角色")
    @PostMapping("/roles")
    public R<DnRole> createRole(@RequestBody DnRole role) {
        if (role.getRoleCode() == null || role.getRoleCode().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "角色编码不能为空");
        }
        return R.ok(rbacService.createRole(role));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/roles/{id}")
    public R<DnRole> updateRole(@PathVariable Long id, @RequestBody DnRole role) {
        role.setId(id);
        return R.ok(rbacService.updateRole(role));
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/roles/{id}")
    public R<String> deleteRole(@PathVariable Long id) {
        rbacService.deleteRole(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "设置角色权限点")
    @PostMapping("/roles/{id}/perms")
    public R<String> setRolePerms(@PathVariable Long id, @RequestBody SetPermsRequest req) {
        rbacService.setRolePerms(id, req.getPerms());
        return R.ok("设置成功");
    }

    @Operation(summary = "全站权限点清单(按模块分组, 供角色配权限树勾选)")
    @GetMapping("/perms/catalog")
    public R<List<Map<String, Object>>> permsCatalog() {
        return R.ok(PermCatalog.grouped());
    }

    // ---------------- 请求体 ----------------

    @Data
    public static class AssignRolesRequest {
        private List<Long> roleIds;
    }

    @Data
    public static class SetPermsRequest {
        private List<String> perms;
    }
}
