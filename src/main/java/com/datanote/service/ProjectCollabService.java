package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectAnnouncementMapper;
import com.datanote.mapper.DnProjectInviteMapper;
import com.datanote.mapper.DnProjectMemberMapper;
import com.datanote.model.DnProjectAnnouncement;
import com.datanote.model.DnProjectInvite;
import com.datanote.model.DnProjectMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 项目协作：公告 + 成员邀请/加入审批。 */
@Service
@RequiredArgsConstructor
public class ProjectCollabService {

    private final DnProjectAnnouncementMapper announcementMapper;
    private final DnProjectInviteMapper inviteMapper;
    private final DnProjectMemberMapper memberMapper;
    private final ProjectService projectService;

    // ===== 公告 =====
    public List<DnProjectAnnouncement> listAnnouncements(Long projectId) {
        projectService.getById(projectId);
        return announcementMapper.selectList(new LambdaQueryWrapper<DnProjectAnnouncement>()
                .eq(DnProjectAnnouncement::getProjectId, projectId).orderByDesc(DnProjectAnnouncement::getId));
    }

    public DnProjectAnnouncement createAnnouncement(Long projectId, DnProjectAnnouncement a) {
        projectService.getById(projectId);
        if (a.getTitle() == null || a.getTitle().trim().isEmpty()) throw new IllegalArgumentException("公告标题不能为空");
        a.setId(null);
        a.setProjectId(projectId);
        a.setTitle(a.getTitle().trim());
        if (a.getPriority() == null) a.setPriority("NORMAL");
        a.setCreatedBy(ProjectService.currentUser());
        announcementMapper.insert(a);
        return a;
    }

    public void deleteAnnouncement(Long projectId, Long annId) {
        DnProjectAnnouncement a = announcementMapper.selectById(annId);
        if (a == null) return;
        if (!projectId.equals(a.getProjectId())) throw new IllegalArgumentException("公告不属于该项目");
        announcementMapper.deleteById(annId);
    }

    // ===== 邀请 =====
    public List<DnProjectInvite> listInvites(Long projectId) {
        projectService.getById(projectId);
        return inviteMapper.selectList(new LambdaQueryWrapper<DnProjectInvite>()
                .eq(DnProjectInvite::getProjectId, projectId).orderByDesc(DnProjectInvite::getId));
    }

    public DnProjectInvite createInvite(Long projectId, String role, String invitee) {
        projectService.getById(projectId);
        if (!ProjectRoles.isValid(role)) throw new IllegalArgumentException("非法项目角色: " + role);
        DnProjectInvite inv = new DnProjectInvite();
        inv.setProjectId(projectId);
        inv.setProjectRole(role);
        inv.setInvitee(invitee == null || invitee.trim().isEmpty() ? null : invitee.trim());
        inv.setToken(UUID.randomUUID().toString().replace("-", ""));
        inv.setStatus("PENDING");
        inv.setCreatedBy(ProjectService.currentUser());
        inviteMapper.insert(inv);
        return inv;
    }

    /** 接受邀请：被邀请人(或当前用户)按角色加入项目成员。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public synchronized void acceptInvite(String token) {
        DnProjectInvite inv = inviteMapper.selectOne(new LambdaQueryWrapper<DnProjectInvite>().eq(DnProjectInvite::getToken, token));
        if (inv == null) throw new IllegalArgumentException("邀请不存在");
        if (!"PENDING".equals(inv.getStatus())) throw new IllegalArgumentException("邀请已处理");
        String user = inv.getInvitee() != null ? inv.getInvitee() : ProjectService.currentUser();
        Long dup = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, inv.getProjectId()).eq(DnProjectMember::getUsername, user));
        if (dup == null || dup == 0) {
            DnProjectMember m = new DnProjectMember();
            m.setProjectId(inv.getProjectId());
            m.setUsername(user);
            m.setProjectRole(inv.getProjectRole());
            m.setAddedBy(ProjectService.currentUser());
            memberMapper.insert(m);
        }
        inv.setStatus("ACCEPTED");
        inv.setHandledAt(LocalDateTime.now());
        inv.setHandledBy(ProjectService.currentUser());
        inviteMapper.updateById(inv);
    }

    public void updateInviteStatus(Long projectId, Long inviteId, String status) {
        DnProjectInvite inv = inviteMapper.selectById(inviteId);
        if (inv == null) throw new IllegalArgumentException("邀请不存在");
        if (!projectId.equals(inv.getProjectId())) throw new IllegalArgumentException("邀请不属于该项目");
        if (!"REJECTED".equals(status) && !"CANCELED".equals(status)) throw new IllegalArgumentException("非法操作");
        inv.setStatus(status);
        inv.setHandledAt(LocalDateTime.now());
        inv.setHandledBy(ProjectService.currentUser());
        inviteMapper.updateById(inv);
    }
}
