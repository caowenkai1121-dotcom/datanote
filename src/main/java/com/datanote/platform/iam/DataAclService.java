package com.datanote.platform.iam;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.config.AuthProperties;
import com.datanote.platform.iam.mapper.DnDataGrantMapper;
import com.datanote.platform.iam.model.DnDataGrant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据权限(资源级访问控制)服务 — 默认公开/黑名单。
 * 某资源(resourceType+resourceId)有 >=1 条授权即"受限": 仅被授权主体(角色/用户) + 超管 + data:all 可访问;
 * 无授权 = 公开。管理需 data:grant 权限点(由拦截器在 /api/data-acl 上把关)。
 */
@Service
@RequiredArgsConstructor
public class DataAclService {

    private final DnDataGrantMapper grantMapper;
    private final RbacService rbacService;
    private final AuthProperties authProperties;

    /** 当前调用者是否豁免所有数据限制(开放态/匿名/超管/data:all)。 */
    private boolean bypass() {
        if (!authProperties.isEnabled()) return true;
        String u = CurrentUserUtil.currentUser();
        if (u == null || "anonymous".equals(u)) return false;
        try {
            Set<String> perms = rbacService.getUserPermsByUsername(u);
            return perms.contains("*") || perms.contains("data:all");
        } catch (Exception e) {
            return false;   // 鉴权类判定异常 fail-closed
        }
    }

    /** 单资源访问校验: 公开(无授权)或被授权(用户名/角色命中)或豁免 → true。 */
    public boolean canAccess(String resourceType, String resourceId) {
        if (resourceId == null) return true;
        if (bypass()) return true;
        List<DnDataGrant> grants = grantMapper.selectList(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId));
        if (grants == null || grants.isEmpty()) return true;   // 默认公开: 无授权=不受限
        String u = CurrentUserUtil.currentUser();
        List<String> roles = safeRoles(u);
        for (DnDataGrant g : grants) {
            if ("USER".equals(g.getPrincipalType()) && u != null && u.equals(g.getPrincipal())) return true;
            if ("ROLE".equals(g.getPrincipalType()) && roles.contains(g.getPrincipal())) return true;
        }
        return false;
    }

    /**
     * 批量过滤: 返回当前调用者"不可访问"的 resourceId 集(受限且未授权)。供列表查询排除鬼影。
     * 一次性加载该类型全部授权, 内存判定, 避免逐条 N+1。
     */
    public Set<String> deniedIds(String resourceType) {
        Set<String> denied = new HashSet<>();
        if (bypass()) return denied;
        List<DnDataGrant> all = grantMapper.selectList(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType));
        if (all == null || all.isEmpty()) return denied;
        String u = CurrentUserUtil.currentUser();
        List<String> roles = safeRoles(u);
        // 按资源聚合: 该资源是否对我开放
        java.util.Map<String, Boolean> allowByRes = new java.util.HashMap<>();
        for (DnDataGrant g : all) {
            String rid = g.getResourceId();
            boolean hit = ("USER".equals(g.getPrincipalType()) && u != null && u.equals(g.getPrincipal()))
                    || ("ROLE".equals(g.getPrincipalType()) && roles.contains(g.getPrincipal()));
            allowByRes.merge(rid, hit, (a, b) -> a || b);
        }
        for (java.util.Map.Entry<String, Boolean> e : allowByRes.entrySet()) {
            if (!e.getValue()) denied.add(e.getKey());   // 受限(有授权)但我不在名单 → 拒
        }
        return denied;
    }

    private List<String> safeRoles(String username) {
        if (username == null) return new ArrayList<>();
        try {
            List<String> r = rbacService.getUserRoleCodesByUsername(username);
            return r == null ? new ArrayList<>() : r;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---------- 显式入参重载(供 agent 异步线程用, 不读 ThreadLocal) ----------

    /** 显式入参版 bypass: 不读 ThreadLocal, 直接用传入的 caller/perms 判定。 */
    private boolean bypassAs(String caller, Set<String> perms) {
        if (!authProperties.isEnabled()) return true;
        if (caller == null || "anonymous".equals(caller)) return false;
        if (perms == null) return false;   // null=perms 未解析(非"已解析但空集"), fail-closed 拒绝; 调用方应传空 Set 表示"已解析无特殊权限"
        return perms.contains("*") || perms.contains("data:all");
    }

    /** 单资源访问校验(显式入参, 不读 ThreadLocal)。 */
    public boolean canAccessAs(String caller, List<String> roles, Set<String> perms, String resourceType, String resourceId) {
        if (resourceId == null) return true;
        if (bypassAs(caller, perms)) return true;
        List<DnDataGrant> grants = grantMapper.selectList(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId));
        if (grants == null || grants.isEmpty()) return true;   // 默认公开
        List<String> rs = roles == null ? new ArrayList<>() : roles;
        for (DnDataGrant g : grants) {
            if ("USER".equals(g.getPrincipalType()) && caller != null && caller.equals(g.getPrincipal())) return true;
            if ("ROLE".equals(g.getPrincipalType()) && rs.contains(g.getPrincipal())) return true;
        }
        return false;
    }

    /** 批量过滤(显式入参版): 返回受限且未授权的 resourceId 集。 */
    public Set<String> deniedIdsAs(String caller, List<String> roles, Set<String> perms, String resourceType) {
        Set<String> denied = new HashSet<>();
        if (bypassAs(caller, perms)) return denied;
        List<DnDataGrant> all = grantMapper.selectList(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType));
        if (all == null || all.isEmpty()) return denied;
        List<String> rs = roles == null ? new ArrayList<>() : roles;
        java.util.Map<String, Boolean> allowByRes = new java.util.HashMap<>();
        for (DnDataGrant g : all) {
            boolean hit = ("USER".equals(g.getPrincipalType()) && caller != null && caller.equals(g.getPrincipal()))
                    || ("ROLE".equals(g.getPrincipalType()) && rs.contains(g.getPrincipal()));
            allowByRes.merge(g.getResourceId(), hit, (a, b) -> a || b);
        }
        for (java.util.Map.Entry<String, Boolean> e : allowByRes.entrySet()) {
            if (!e.getValue()) denied.add(e.getKey());
        }
        return denied;
    }

    // ---------- 管理(需 data:grant, 由拦截器把关) ----------

    /** 某资源的授权清单。 */
    public List<DnDataGrant> listGrants(String resourceType, String resourceId) {
        return grantMapper.selectList(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId)
                .orderByAsc("principal_type").orderByAsc("principal"));
    }

    /** 覆盖式设置某资源授权(空列表=取消受限, 恢复公开)。 */
    public void setGrants(String resourceType, String resourceId, List<DnDataGrant> grants, String operator) {
        if (resourceType == null || resourceId == null) return;
        grantMapper.delete(new QueryWrapper<DnDataGrant>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId));
        if (grants == null) return;
        Set<String> seen = new HashSet<>();
        for (DnDataGrant g : grants) {
            if (g == null || g.getPrincipal() == null || g.getPrincipal().trim().isEmpty()) continue;
            String pt = "USER".equals(g.getPrincipalType()) ? "USER" : "ROLE";
            String key = pt + ":" + g.getPrincipal().trim();
            if (!seen.add(key)) continue;
            DnDataGrant row = new DnDataGrant();
            row.setResourceType(resourceType);
            row.setResourceId(resourceId);
            row.setPrincipalType(pt);
            row.setPrincipal(g.getPrincipal().trim());
            row.setCreatedBy(operator);
            row.setCreatedAt(LocalDateTime.now());
            grantMapper.insert(row);
        }
    }
}
