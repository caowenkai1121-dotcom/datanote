package com.datanote.platform.iam;

import com.datanote.platform.config.DbUserDetailsService;
import com.datanote.platform.config.SecurityConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.platform.iam.mapper.DnRoleMapper;
import com.datanote.platform.iam.mapper.DnRolePermMapper;
import com.datanote.platform.iam.mapper.DnUserMapper;
import com.datanote.platform.iam.mapper.DnUserRoleMapper;
import com.datanote.platform.iam.model.DnRole;
import com.datanote.platform.iam.model.DnRolePerm;
import com.datanote.platform.iam.model.DnUser;
import com.datanote.platform.iam.model.DnUserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 服务 — 用户/角色 CRUD、角色分配、权限集查询、权限校验。
 * 密码一律经 BCrypt 哈希入库，绝不存明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacService {

    private final DnUserMapper userMapper;
    private final DnRoleMapper roleMapper;
    private final DnUserRoleMapper userRoleMapper;
    private final DnRolePermMapper rolePermMapper;
    /** BCrypt 无状态，自建实例避免注入 SecurityConfig 的 PasswordEncoder Bean 造成循环依赖
     *  (SecurityConfig→DbUserDetailsService→RbacService→PasswordEncoder)。哈希跨实例兼容。 */
    private static final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 纯函数：判断权限集合是否满足某个权限点。
     * 含 '*' 通配则全通过；含精确匹配则通过；need 为空/null 一律拒绝。
     */
    public static boolean hasPermission(Set<String> perms, String need) {
        if (need == null || need.isEmpty() || perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.contains("*") || perms.contains(need);
    }

    // ---------------- 用户 ----------------

    public List<DnUser> listUsers() {
        QueryWrapper<DnUser> qw = new QueryWrapper<>();
        qw.orderByAsc("id");
        List<DnUser> users = userMapper.selectList(qw);
        if (users == null) {
            return new ArrayList<>();
        }
        // 不回传密码哈希
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    public DnUser findByUsername(String username) {
        // 用户名空白时无可查之据，直接返回 null（与"找不到用户"语义一致，避免无效全表 selectOne）
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        QueryWrapper<DnUser> qw = new QueryWrapper<>();
        qw.eq("username", username);
        return userMapper.selectOne(qw);
    }

    /**
     * 创建用户：密码 BCrypt 哈希后入库。
     */
    public DnUser createUser(DnUser user) {
        if (user == null) {
            throw new BusinessException("创建用户失败：用户对象不能为空");
        }
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new BusinessException("创建用户失败：用户名不能为空");
        }
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        user.setId(null);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    /**
     * 更新用户：仅当传入了非空 password 时才重新哈希，否则保留原密码不动。
     */
    public DnUser updateUser(DnUser user) {
        if (user == null) {
            throw new BusinessException("更新用户失败：用户对象不能为空");
        }
        if (user.getId() == null) {
            throw new BusinessException("更新用户失败：用户ID不能为空");
        }
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null); // null 字段 MyBatis-Plus 默认不更新
        }
        user.setUpdatedAt(LocalDateTime.now());
        user.setCreatedAt(null);
        userMapper.updateById(user);
        user.setPassword(null); // 清空密码哈希,避免回传到响应(与 createUser 一致)
        return user;
    }

    /**
     * 删除用户（级联删除其角色绑定）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("删除用户失败：用户ID不能为空");
        }
        QueryWrapper<DnUserRole> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        userRoleMapper.delete(qw);
        userMapper.deleteById(userId);
    }

    // ---------------- 角色 ----------------

    public List<DnRole> listRoles() {
        QueryWrapper<DnRole> qw = new QueryWrapper<>();
        qw.orderByAsc("id");
        List<DnRole> roles = roleMapper.selectList(qw);
        return roles != null ? roles : new ArrayList<>();
    }

    public DnRole createRole(DnRole role) {
        if (role == null) {
            throw new BusinessException("创建角色失败：角色对象不能为空");
        }
        if (role.getRoleCode() == null || role.getRoleCode().trim().isEmpty()) {
            throw new BusinessException("创建角色失败：角色编码不能为空");
        }
        role.setId(null);
        roleMapper.insert(role);
        return role;
    }

    public DnRole updateRole(DnRole role) {
        if (role == null) {
            throw new BusinessException("更新角色失败：角色对象不能为空");
        }
        if (role.getId() == null) {
            throw new BusinessException("更新角色失败：角色ID不能为空");
        }
        roleMapper.updateById(role);
        return role;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        if (roleId == null) {
            throw new BusinessException("删除角色失败：角色ID不能为空");
        }
        QueryWrapper<DnUserRole> urQw = new QueryWrapper<>();
        urQw.eq("role_id", roleId);
        userRoleMapper.delete(urQw);
        QueryWrapper<DnRolePerm> rpQw = new QueryWrapper<>();
        rpQw.eq("role_id", roleId);
        rolePermMapper.delete(rpQw);
        roleMapper.deleteById(roleId);
    }

    /**
     * 查询角色的权限点列表。
     */
    public List<String> listRolePerms(Long roleId) {
        if (roleId == null) {
            return new ArrayList<>();
        }
        QueryWrapper<DnRolePerm> qw = new QueryWrapper<>();
        qw.eq("role_id", roleId);
        List<DnRolePerm> rows = rolePermMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        return rows.stream()
                .map(DnRolePerm::getPermCode)
                .collect(Collectors.toList());
    }

    /**
     * 覆盖式设置角色权限点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void setRolePerms(Long roleId, List<String> permCodes) {
        if (roleId == null) {
            throw new BusinessException("设置角色权限失败：角色ID不能为空");
        }
        QueryWrapper<DnRolePerm> qw = new QueryWrapper<>();
        qw.eq("role_id", roleId);
        rolePermMapper.delete(qw);
        if (permCodes != null) {
            // LinkedHashSet 去重并保留顺序，行为与原 HashSet 去重等价
            for (String code : new LinkedHashSet<>(permCodes)) {
                if (code == null || code.isEmpty()) {
                    continue;
                }
                DnRolePerm rp = new DnRolePerm();
                rp.setRoleId(roleId);
                rp.setPermCode(code);
                rolePermMapper.insert(rp);
            }
        }
    }

    // ---------------- 分配与权限集 ----------------

    /**
     * 覆盖式给用户分配角色（先删后插，事务）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        if (userId == null) {
            throw new BusinessException("分配角色失败：用户ID不能为空");
        }
        QueryWrapper<DnUserRole> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        userRoleMapper.delete(qw);
        if (roleIds != null) {
            // LinkedHashSet 去重并保留顺序，行为与原 HashSet 去重等价
            for (Long roleId : new LinkedHashSet<>(roleIds)) {
                if (roleId == null) {
                    continue;
                }
                DnUserRole ur = new DnUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }

    /**
     * 查询用户的角色 ID 列表。
     */
    public List<Long> getUserRoleIds(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        QueryWrapper<DnUserRole> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        List<DnUserRole> rows = userRoleMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        return rows.stream()
                .map(DnUserRole::getRoleId)
                .collect(Collectors.toList());
    }

    /**
     * 聚合用户所有角色的权限点为去重集合。
     */
    public Set<String> getUserPerms(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }
        QueryWrapper<DnRolePerm> qw = new QueryWrapper<>();
        qw.in("role_id", roleIds);
        List<DnRolePerm> rows = rolePermMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptySet();
        }
        return rows.stream()
                .map(DnRolePerm::getPermCode)
                .collect(Collectors.toSet());
    }

    /**
     * 按用户名查权限集（找不到用户返回空集）。
     */
    public Set<String> getUserPermsByUsername(String username) {
        DnUser user = findByUsername(username);
        if (user == null) {
            return Collections.emptySet();
        }
        return getUserPerms(user.getId());
    }

    /**
     * 按用户名查角色编码集（找不到返回空列表）。
     */
    public List<String> getUserRoleCodesByUsername(String username) {
        DnUser user = findByUsername(username);
        if (user == null) {
            return new ArrayList<>();
        }
        List<Long> roleIds = getUserRoleIds(user.getId());
        if (roleIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<DnRole> roles = roleMapper.selectBatchIds(roleIds);
        if (roles == null || roles.isEmpty()) {
            return new ArrayList<>();
        }
        return roles.stream()
                .map(DnRole::getRoleCode)
                .collect(Collectors.toList());
    }
}
