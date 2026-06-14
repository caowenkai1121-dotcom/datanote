package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.model.DnProjectMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目成员管理：增/删/改角色/列表；护栏——角色合法、不重复、不可移除/降级最后一个 OWNER。 */
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final DnProjectMemberMapper memberMapper;
    private final ProjectService projectService;
    private final com.datanote.platform.iam.mapper.DnUserMapper userMapper;   // P4 成员须为 RBAC 真实用户

    public List<DnProjectMember> list(Long projectId) {
        projectService.getById(projectId); // 校验项目存在
        return memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId)
                .orderByAsc(DnProjectMember::getId));
    }

    public DnProjectMember add(Long projectId, String username, String role) {
        projectService.getById(projectId);
        if (username == null || username.trim().isEmpty()) throw new IllegalArgumentException("用户名不能为空");
        username = username.trim();
        if (!ProjectRoles.isValid(role)) throw new IllegalArgumentException("非法项目角色: " + role);
        // P4 防呆: 成员必须是 RBAC 真实用户(防手填错名→任务指派/审批权落空)
        Long exists = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.datanote.platform.iam.model.DnUser>()
                .eq("username", username));
        if (exists == null || exists == 0) throw new IllegalArgumentException("用户不存在于用户体系: " + username + "(请先在系统管理-用户管理创建)");
        Long dup = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId).eq(DnProjectMember::getUsername, username));
        if (dup != null && dup > 0) throw new IllegalArgumentException("成员已存在: " + username);
        DnProjectMember m = new DnProjectMember();
        m.setProjectId(projectId);
        m.setUsername(username);
        m.setProjectRole(role);
        m.setAddedBy(ProjectService.currentUser());
        memberMapper.insert(m);
        return m;
    }

    public void changeRole(Long projectId, Long memberId, String role) {
        projectService.getById(projectId);   // 数据级访问校验(canAccessProject), 防 IDOR
        if (!ProjectRoles.isValid(role)) throw new IllegalArgumentException("非法项目角色: " + role);
        DnProjectMember m = memberMapper.selectById(memberId);
        if (m == null) throw new IllegalArgumentException("成员不存在: " + memberId);
        if (!projectId.equals(m.getProjectId())) throw new IllegalArgumentException("成员不属于该项目");
        if ("OWNER".equals(m.getProjectRole()) && !"OWNER".equals(role) && lastOwner(m.getProjectId(), memberId)) {
            throw new IllegalArgumentException("项目须保留至少一个负责人(OWNER)");
        }
        m.setProjectRole(role);
        memberMapper.updateById(m);
    }

    public void remove(Long projectId, Long memberId) {
        projectService.getById(projectId);   // 数据级访问校验(canAccessProject), 防 IDOR
        DnProjectMember m = memberMapper.selectById(memberId);
        if (m == null) return;
        if (!projectId.equals(m.getProjectId())) throw new IllegalArgumentException("成员不属于该项目");
        if ("OWNER".equals(m.getProjectRole()) && lastOwner(m.getProjectId(), memberId)) {
            throw new IllegalArgumentException("不可移除最后一个负责人(OWNER)");
        }
        memberMapper.deleteById(memberId);
    }

    private boolean lastOwner(Long projectId, Long selfMemberId) {
        Long owners = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId)
                .eq(DnProjectMember::getProjectRole, "OWNER")
                .ne(DnProjectMember::getId, selfMemberId));
        return owners == null || owners == 0;
    }
}
