package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectMapper;
import com.datanote.mapper.DnProjectMemberMapper;
import com.datanote.model.DnProject;
import com.datanote.model.DnProjectMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 项目 CRUD：校验、自动编码、owner 自动入成员、归档/软删除。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final DnProjectMapper projectMapper;
    private final DnProjectMemberMapper memberMapper;

    /** 列表（默认排除已删除；status 非空再过滤），按创建倒序。 */
    public List<DnProject> list(String status) {
        LambdaQueryWrapper<DnProject> w = new LambdaQueryWrapper<DnProject>()
                .ne(DnProject::getStatus, "DELETED");
        if (status != null && !status.trim().isEmpty()) {
            w.eq(DnProject::getStatus, status.trim());
        }
        w.orderByDesc(DnProject::getId);
        return projectMapper.selectList(w);
    }

    public DnProject getById(Long id) {
        DnProject p = projectMapper.selectById(id);
        if (p == null || "DELETED".equals(p.getStatus())) {
            throw new IllegalArgumentException("项目不存在: " + id);
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
        p.setStatus("ARCHIVED");
        p.setArchivedAt(LocalDateTime.now());
        projectMapper.updateById(p);
    }

    /** 软删除：标记 DELETED。 */
    public void delete(Long id) {
        DnProject p = getById(id);
        p.setStatus("DELETED");
        p.setDeletedAt(LocalDateTime.now());
        projectMapper.updateById(p);
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
