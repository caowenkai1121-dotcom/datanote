package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.project.mapper.DnProjectMapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 项目 CRUD：校验、自动编码、owner 自动入成员、归档/软删除、数据级权限(行级范围)。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final DnProjectMapper projectMapper;
    private final DnProjectMemberMapper memberMapper;
    private final com.datanote.platform.config.AuthProperties authProperties;
    private final com.datanote.platform.iam.RbacService rbacService;

    // ---------------- 数据级权限(项目维度行级范围) ----------------

    /**
     * 当前用户是否可见全部项目。开放模式/超管(*)/拥有 project:all-data 时可见全部;
     * 否则只可见自己负责(owner)、自己创建(created_by)或参与(成员表)的项目。
     */
    boolean canSeeAllProjects() {
        if (!authProperties.isEnabled()) return true;
        String u = currentUser();
        if (u == null || "anonymous".equals(u)) return true;   // 未认证由 SecurityConfig 拦, 此处不重复
        java.util.Set<String> perms;
        try {
            perms = rbacService.getUserPermsByUsername(u);
        } catch (Exception e) {
            return true;   // 权限查询异常时不阻断业务(fail-open 读场景)
        }
        if (perms.isEmpty() && u.equals(authProperties.getUsername())) return true;   // 内存兜底 admin
        return com.datanote.platform.iam.RbacService.hasPermission(perms, "project:all-data");
    }

    /** 当前用户参与的项目 ID 集(成员表)。 */
    private List<Long> memberProjectIds(String user) {
        List<DnProjectMember> rows = memberMapper.selectList(
                new LambdaQueryWrapper<DnProjectMember>().eq(DnProjectMember::getUsername, user));
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (rows != null) for (DnProjectMember m : rows) if (m != null && m.getProjectId() != null) ids.add(m.getProjectId());
        return ids;
    }

    /** 当前用户能否访问指定项目(负责/创建/成员之一)。 */
    boolean canAccessProject(DnProject p) {
        if (canSeeAllProjects()) return true;
        String u = currentUser();
        if (u == null) return false;
        if (u.equals(p.getOwner()) || u.equals(p.getCreatedBy())) return true;
        Long cnt = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, p.getId()).eq(DnProjectMember::getUsername, u));
        return cnt != null && cnt > 0;
    }

    /** 列表（默认排除已删除；status 非空再过滤），按创建倒序。数据级: 无 project:all-data 只看自己相关的。 */
    public List<DnProject> list(String status) {
        LambdaQueryWrapper<DnProject> w = new LambdaQueryWrapper<DnProject>()
                .ne(DnProject::getStatus, "DELETED");
        if (status != null && !status.trim().isEmpty()) {
            w.eq(DnProject::getStatus, status.trim());
        }
        if (!canSeeAllProjects()) {
            String u = currentUser();
            List<Long> ids = memberProjectIds(u);
            w.and(x -> {
                x.eq(DnProject::getOwner, u).or().eq(DnProject::getCreatedBy, u);
                if (!ids.isEmpty()) x.or().in(DnProject::getId, ids);
            });
        }
        w.orderByDesc(DnProject::getId);
        return projectMapper.selectList(w);
    }

    public DnProject getById(Long id) {
        if (id == null) {
            throw new BusinessException("项目ID不能为空");
        }
        DnProject p = projectMapper.selectById(id);
        if (p == null || "DELETED".equals(p.getStatus())) {
            throw new BusinessException("项目不存在: " + id);
        }
        if (!canAccessProject(p)) {
            throw new BusinessException("无权访问该项目(非项目成员), 请联系项目负责人添加");
        }
        return p;
    }

    public DnProject save(DnProject p) {
        if (p.getProjectName() == null || p.getProjectName().trim().isEmpty()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        p.setProjectName(p.getProjectName().trim());
        boolean isNew = p.getId() == null;
        String user = currentUser();
        if (isNew) {
            String code = (p.getProjectCode() == null || p.getProjectCode().trim().isEmpty())
                    ? genUniqueCode(p.getProjectName()) : ensureCodeUnique(p.getProjectCode().trim(), null);
            p.setProjectCode(code);
            if (p.getStatus() == null) p.setStatus("ACTIVE");
            if (p.getProjectType() == null) p.setProjectType("GENERAL");
            if (p.getEnv() == null) p.setEnv("DEV");
            if (p.getSensitivity() == null) p.setSensitivity("NORMAL");
            if (p.getOwner() == null || p.getOwner().trim().isEmpty()) p.setOwner(user);
            p.setCreatedBy(user);
            projectMapper.insert(p);
            addOwnerMember(p, user);
        } else {
            DnProject old = getById(p.getId());
            if (p.getProjectCode() != null && !p.getProjectCode().trim().isEmpty()
                    && !p.getProjectCode().trim().equals(old.getProjectCode())) {
                p.setProjectCode(ensureCodeUnique(p.getProjectCode().trim(), p.getId()));
            } else {
                p.setProjectCode(old.getProjectCode());
            }
            // 不可经此改动归档/删除态与时间戳
            p.setStatus(old.getStatus());
            p.setCreatedBy(old.getCreatedBy());
            p.setCreatedAt(old.getCreatedAt());
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
        }
        return p;
    }

    /** 归档：仅 ACTIVE→ARCHIVED。 */
    public void archive(Long id) {
        DnProject p = getById(id);
        requireEditRole(p);
        p.setStatus("ARCHIVED");
        p.setArchivedAt(LocalDateTime.now());
        projectMapper.updateById(p);
    }

    /** 软删除：标记 DELETED。 */
    public void delete(Long id) {
        DnProject p = getById(id);
        requireEditRole(p);
        p.setStatus("DELETED");
        p.setDeletedAt(LocalDateTime.now());
        projectMapper.updateById(p);
    }

    /** 项目角色门禁：归档/软删须 project:edit(仅 OWNER/ADMIN)；超管/project:all-data 放行。 */
    private void requireEditRole(DnProject p) {
        if (canSeeAllProjects()) return;
        String u = currentUser();
        String role;
        if (u != null && (u.equals(p.getOwner()) || u.equals(p.getCreatedBy()))) {
            role = "OWNER";
        } else {
            DnProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<DnProjectMember>()
                    .eq(DnProjectMember::getProjectId, p.getId())
                    .eq(DnProjectMember::getUsername, u).last("LIMIT 1"));
            role = m == null ? null : m.getProjectRole();
        }
        if (!ProjectRoles.can(role, "project:edit")) {
            throw new BusinessException("无权归档/删除该项目(需项目负责人/管理员)");
        }
    }

    private void addOwnerMember(DnProject p, String user) {
        String owner = p.getOwner();
        if (owner == null || owner.trim().isEmpty()) return;
        try {
            Long cnt = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                    .eq(DnProjectMember::getProjectId, p.getId())
                    .eq(DnProjectMember::getUsername, owner));
            if (cnt == null || cnt == 0) {
                DnProjectMember m = new DnProjectMember();
                m.setProjectId(p.getId());
                m.setUsername(owner);
                m.setProjectRole("OWNER");
                m.setAddedBy(user);
                memberMapper.insert(m);
            }
        } catch (Exception e) {
            log.warn("owner 自动入成员失败 projectId={}: {}", p.getId(), e.getMessage());
        }
    }

    private String genUniqueCode(String name) {
        return ensureCodeUnique(ProjectCodeGenerator.slug(name), null);
    }

    /** 保证 code 全局唯一（排除自身 id），冲突则追加 _2/_3…。 */
    private String ensureCodeUnique(String base, Long selfId) {
        String code = base;
        int n = 1;
        while (codeExists(code, selfId)) {
            n++;
            code = base + "_" + n;
            if (n > 1000) { // 兜底：避免极端同名下无限递增
                code = base + "_" + System.currentTimeMillis();
                break;
            }
        }
        return code;
    }

    private boolean codeExists(String code, Long selfId) {
        LambdaQueryWrapper<DnProject> w = new LambdaQueryWrapper<DnProject>()
                .eq(DnProject::getProjectCode, code);
        if (selfId != null) w.ne(DnProject::getId, selfId);
        Long c = projectMapper.selectCount(w);
        return c != null && c > 0;
    }

    static String currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return auth.getName();
            }
        } catch (Exception ignore) {
            // 取不到身份按 admin 兜底（鉴权当前开放）
        }
        return "admin";
    }
}
