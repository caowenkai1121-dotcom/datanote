package com.datanote.platform.iam;

import com.datanote.platform.iam.model.DnRole;
import com.datanote.platform.iam.model.DnUser;
import com.datanote.platform.iam.model.DnUserRole;
import com.datanote.common.model.R;
import com.datanote.platform.iam.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    private final com.datanote.platform.iam.mapper.DnUserRoleMapper userRoleMapper;   // 用户列表批量查角色绑定(避免 N+1)
    private final com.datanote.platform.audit.AuditService auditService;
    private final PermInterceptor permInterceptor;   // 账号/权限变更后清缓存, 使停用·删除·改权限立即生效

    /** 当前操作人(用于权限变更审计)。 */
    private String actor() {
        return com.datanote.platform.iam.CurrentUserUtil.currentUser();
    }

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
            data.put("perms", rbacService.resolvePerms(auth));
        }
        return R.ok(data);
    }

    // ---------------- 用户 ----------------

    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public R<List<Map<String, Object>>> listUsers() {
        List<DnUser> users = rbacService.listUsers();
        // 角色 id→name 映射(一次查全, 避免每用户查库)
        Map<Long, String> roleNameById = new HashMap<>();
        for (DnRole r : rbacService.listRoles()) roleNameById.put(r.getId(), r.getRoleName());
        // 一次批量查全部用户的角色绑定, 构建 userId→roleIds 映射(避免每用户单查的 N+1)
        Map<Long, List<Long>> roleIdsByUser = new HashMap<>();
        List<Long> userIds = users.stream().map(DnUser::getId).filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (!userIds.isEmpty()) {
            List<DnUserRole> bindings = userRoleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DnUserRole>().in("user_id", userIds));
            if (bindings != null) {
                for (DnUserRole ur : bindings) {
                    if (ur.getUserId() == null || ur.getRoleId() == null) continue;
                    roleIdsByUser.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>()).add(ur.getRoleId());
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (DnUser u : users) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("status", u.getStatus());
            m.put("createdAt", u.getCreatedAt());
            m.put("lastLoginAt", u.getLastLoginAt());
            List<Long> roleIds = roleIdsByUser.getOrDefault(u.getId(), new ArrayList<>());
            m.put("roleIds", roleIds);
            List<String> roleNames = new ArrayList<>();
            for (Long rid : roleIds) { String n = roleNameById.get(rid); if (n != null) roleNames.add(n); }
            m.put("roleNames", roleNames);
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
        // 停用保护: 不可停用自己; 不可停用最后一名启用的超级管理员
        if (user.getStatus() != null && user.getStatus() == 0) {
            DnUser self = rbacService.findByUsername(actor());
            if (self != null && self.getId().equals(id)) {
                return R.fail(R.CODE_BAD_REQUEST, "不能停用当前登录的自己");
            }
            if (rbacService.isSuperAdmin(id) && rbacService.countActiveSuperAdmins() <= 1) {
                return R.fail(R.CODE_BAD_REQUEST, "不能停用最后一名超级管理员, 系统将无人可管理");
            }
        }
        user.setId(id);
        user.setUsername(null); // 用户名不可改
        DnUser r = rbacService.updateUser(user);
        permInterceptor.evictAll();   // 停用立即生效(存量会话下个请求即被踢)
        if (user.getPassword() != null) {
            auditService.record(actor(), "PERM_CHANGE", "PUT", "/api/rbac/users/" + id, null, 200, "重置用户#" + id + " 密码");
        }
        return R.ok(r);
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/users/{id}")
    public R<String> deleteUser(@PathVariable Long id) {
        DnUser self = rbacService.findByUsername(actor());
        if (self != null && self.getId().equals(id)) {
            return R.fail(R.CODE_BAD_REQUEST, "不能删除当前登录的自己");
        }
        if (rbacService.isSuperAdmin(id) && rbacService.countActiveSuperAdmins() <= 1) {
            return R.fail(R.CODE_BAD_REQUEST, "不能删除最后一名超级管理员");
        }
        rbacService.deleteUser(id);
        permInterceptor.evictAll();   // 删除立即生效
        auditService.record(actor(), "PERM_CHANGE", "DELETE", "/api/rbac/users/" + id, null, 200, "删除用户#" + id);
        return R.ok("删除成功");
    }

    @Operation(summary = "给用户分配角色")
    @PostMapping("/users/{id}/roles")
    public R<String> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest req) {
        // 越权自提升防护: 非超管不得把"会带来自己没有的权限点"的角色授予他人
        DnUser self = rbacService.findByUsername(actor());
        boolean selfSuper = self != null && rbacService.isSuperAdmin(self.getId());
        if (!selfSuper && self != null && req.getRoleIds() != null) {
            java.util.Set<String> myPerms = rbacService.getUserPerms(self.getId());
            for (Long rid : req.getRoleIds()) {
                for (String p : rbacService.listRolePerms(rid)) {
                    if (!RbacService.hasPermission(myPerms, p)) {
                        return R.fail(R.CODE_FORBIDDEN, "不能授予你本人不具备的权限(" + p + ")");
                    }
                }
            }
        }
        rbacService.assignRoles(id, req.getRoleIds());
        permInterceptor.evictAll();   // 角色变更立即生效(原最迟 30s)
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/users/" + id + "/roles", null, 200,
                "为用户#" + id + " 分配角色 " + req.getRoleIds());
        return R.ok("分配成功");
    }

    // ---------------- 批量用户操作 ----------------

    @Operation(summary = "批量启用/停用用户")
    @PostMapping("/users/batch-status")
    public R<Map<String, Object>> batchStatus(@RequestBody BatchStatusRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty() || req.getStatus() == null) {
            return R.fail(R.CODE_BAD_REQUEST, "缺少用户或目标状态");
        }
        DnUser self = rbacService.findByUsername(actor());
        Long selfId = self != null ? self.getId() : null;
        int ok = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(req.getIds())) {
            if (id == null) continue;
            if (req.getStatus() == 0) {   // 停用护栏: 不可停自己 / 最后一名超管(逐个查, 停一个少一个语义正确)
                if (selfId != null && selfId.equals(id)) { skipped.add(skip(id, "不能停用当前登录的自己")); continue; }
                if (rbacService.isSuperAdmin(id) && rbacService.countActiveSuperAdmins() <= 1) { skipped.add(skip(id, "最后一名超级管理员")); continue; }
            }
            DnUser u = new DnUser(); u.setId(id); u.setStatus(req.getStatus());
            rbacService.updateUser(u); ok++;
        }
        permInterceptor.evictAll();
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/users/batch-status", null, 200,
                "批量" + (req.getStatus() == 0 ? "停用" : "启用") + " " + ok + " 个用户" + (skipped.isEmpty() ? "" : ", 跳过 " + skipped.size() + " 个"));
        return R.ok(result(ok, skipped));
    }

    @Operation(summary = "批量删除用户")
    @PostMapping("/users/batch-delete")
    public R<Map<String, Object>> batchDelete(@RequestBody IdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) return R.fail(R.CODE_BAD_REQUEST, "未选择用户");
        DnUser self = rbacService.findByUsername(actor());
        Long selfId = self != null ? self.getId() : null;
        int ok = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(req.getIds())) {
            if (id == null) continue;
            if (selfId != null && selfId.equals(id)) { skipped.add(skip(id, "不能删除当前登录的自己")); continue; }
            if (rbacService.isSuperAdmin(id) && rbacService.countActiveSuperAdmins() <= 1) { skipped.add(skip(id, "最后一名超级管理员")); continue; }
            rbacService.deleteUser(id); ok++;
        }
        permInterceptor.evictAll();
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/users/batch-delete", null, 200,
                "批量删除 " + ok + " 个用户" + (skipped.isEmpty() ? "" : ", 跳过 " + skipped.size() + " 个"));
        return R.ok(result(ok, skipped));
    }

    @Operation(summary = "批量追加角色(叠加现有, 不覆盖)")
    @PostMapping("/users/batch-roles")
    public R<Map<String, Object>> batchRoles(@RequestBody BatchRolesRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty() || req.getRoleIds() == null || req.getRoleIds().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "缺少用户或角色");
        }
        // 越权自提升防护: 非超管不得授予自己不具备的权限点(与单个 assignRoles 一致)
        DnUser self = rbacService.findByUsername(actor());
        boolean selfSuper = self != null && rbacService.isSuperAdmin(self.getId());
        if (!selfSuper && self != null) {
            Set<String> myPerms = rbacService.getUserPerms(self.getId());
            for (Long rid : req.getRoleIds()) {
                for (String p : rbacService.listRolePerms(rid)) {
                    if (!RbacService.hasPermission(myPerms, p)) {
                        return R.fail(R.CODE_FORBIDDEN, "不能授予你本人不具备的权限(" + p + ")");
                    }
                }
            }
        }
        int ok = 0;
        for (Long uid : new LinkedHashSet<>(req.getIds())) {
            if (uid == null) continue;
            // 追加式: 现有角色 ∪ 新角色, 去重(批量场景多为"给这批人加某角色", 不应清掉各自其他角色)
            Set<Long> merged = new LinkedHashSet<>(rbacService.getUserRoleIds(uid));
            merged.addAll(req.getRoleIds());
            rbacService.assignRoles(uid, new ArrayList<>(merged));
            ok++;
        }
        permInterceptor.evictAll();
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/users/batch-roles", null, 200,
                "批量为 " + ok + " 个用户追加角色 " + req.getRoleIds());
        return R.ok(result(ok, new ArrayList<>()));
    }

    private Map<String, Object> skip(Long id, String reason) {
        Map<String, Object> m = new HashMap<>(); m.put("id", id); m.put("reason", reason); return m;
    }
    private Map<String, Object> result(int success, List<Map<String, Object>> skipped) {
        Map<String, Object> m = new HashMap<>(); m.put("success", success); m.put("skipped", skipped); return m;
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
            m.put("userCount", rbacService.roleUserCount(r.getId()));   // 删角色前的影响提示
            result.add(m);
        }
        return R.ok(result);
    }

    @Operation(summary = "用户的有效权限汇总(角色∪直授, 跨角色去重, 排障/审查用)")
    @GetMapping("/users/{id}/perms")
    public R<List<String>> userEffectivePerms(@PathVariable Long id) {
        java.util.Set<String> perms = rbacService.getUserPerms(id);
        return R.ok(new ArrayList<>(perms));
    }

    @Operation(summary = "用户直授权限点(不含角色继承)")
    @GetMapping("/users/{id}/direct-perms")
    public R<List<String>> userDirectPerms(@PathVariable Long id) {
        return R.ok(rbacService.getUserDirectPerms(id));
    }

    @Operation(summary = "设置用户直授权限点(覆盖式, 叠加于角色之上)")
    @PostMapping("/users/{id}/direct-perms")
    public R<String> setUserDirectPerms(@PathVariable Long id, @RequestBody SetPermsRequest req) {
        // 越权自提升防护: 非超管不得直授自己不具备的权限点(否则可给自己塞 '*' 提权)
        DnUser self = rbacService.findByUsername(actor());
        boolean selfSuper = self != null && rbacService.isSuperAdmin(self.getId());
        if (!selfSuper && self != null && req.getPerms() != null) {
            java.util.Set<String> myPerms = rbacService.getUserPerms(self.getId());
            for (String p : req.getPerms()) {
                if (!RbacService.hasPermission(myPerms, p)) {
                    return R.fail(R.CODE_FORBIDDEN, "不能授予你本人不具备的权限(" + p + ")");
                }
            }
        }
        rbacService.setUserDirectPerms(id, req.getPerms(), actor());
        permInterceptor.evictAll();   // 直授变更立即生效
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/users/" + id + "/direct-perms", null, 200,
                "设置用户#" + id + " 直授权限点 " + (req.getPerms() == null ? 0 : req.getPerms().size()) + " 个");
        return R.ok("设置成功");
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
        permInterceptor.evictAll();   // 绑定用户的权限随角色删除立即收回
        return R.ok("删除成功");
    }

    @Operation(summary = "设置角色权限点")
    @PostMapping("/roles/{id}/perms")
    public R<String> setRolePerms(@PathVariable Long id, @RequestBody SetPermsRequest req) {
        // 越权自提升防护: 非超管不得给角色加自己不具备的权限点(否则可对自身角色塞 '*' 提权为超管)
        DnUser self = rbacService.findByUsername(actor());
        boolean selfSuper = self != null && rbacService.isSuperAdmin(self.getId());
        if (!selfSuper && self != null && req.getPerms() != null) {
            java.util.Set<String> myPerms = rbacService.getUserPerms(self.getId());
            for (String p : req.getPerms()) {
                if (!RbacService.hasPermission(myPerms, p)) {
                    return R.fail(R.CODE_FORBIDDEN, "不能授予你本人不具备的权限(" + p + ")");
                }
            }
        }
        rbacService.setRolePerms(id, req.getPerms());
        permInterceptor.evictAll();   // 权限点变更立即生效
        auditService.record(actor(), "PERM_CHANGE", "POST", "/api/rbac/roles/" + id + "/perms", null, 200,
                "设置角色#" + id + " 权限点 " + (req.getPerms() == null ? 0 : req.getPerms().size()) + " 个");
        return R.ok("设置成功");
    }

    @Operation(summary = "全站权限点清单(按模块分组, 供角色配权限树勾选)")
    @GetMapping("/perms/catalog")
    public R<List<Map<String, Object>>> permsCatalog() {
        return R.ok(PermCatalog.grouped());
    }

    /**
     * 轻量用户名列表(仅 username/nickname, 启用用户)。登录即可访问(不受 settings:user 限制),
     * 供项目添加成员/指派负责人等协作场景选人 —— 完整用户管理列表(/users)仍需 settings:user。
     */
    @Operation(summary = "可选用户名列表(协作选人, 登录即可)")
    @GetMapping("/usernames")
    public R<List<Map<String, Object>>> usernames() {
        List<DnUser> users = rbacService.listUsers();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DnUser u : users) {
            if (u.getStatus() == null || u.getStatus() != 1) continue;   // 停用用户不可选
            Map<String, Object> m = new HashMap<>();
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            result.add(m);
        }
        return R.ok(result);
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

    @Data
    public static class BatchStatusRequest {
        private List<Long> ids;
        private Integer status;
    }

    @Data
    public static class IdsRequest {
        private List<Long> ids;
    }

    @Data
    public static class BatchRolesRequest {
        private List<Long> ids;
        private List<Long> roleIds;
    }
}
