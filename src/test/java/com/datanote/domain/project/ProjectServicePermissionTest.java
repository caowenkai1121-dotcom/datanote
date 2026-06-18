package com.datanote.domain.project;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.project.mapper.DnProjectMapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectMember;
import com.datanote.platform.config.AuthProperties;
import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServicePermissionTest {

    @Mock private DnProjectMapper projectMapper;
    @Mock private DnProjectMemberMapper memberMapper;
    @Mock private AuthProperties authProperties;
    @Mock private RbacService rbacService;
    @InjectMocks private ProjectService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void canSeeAllProjects_rbacError_failsClosed() {
        loginAs("alice");
        when(authProperties.isEnabled()).thenReturn(true);
        when(rbacService.getUserPermsByUsername("alice")).thenThrow(new RuntimeException("rbac down"));

        assertFalse(service.canSeeAllProjects());
    }

    @Test
    void canSeeAllProjects_noSecurityContext_doesNotUseFallbackAdmin() {
        when(authProperties.isEnabled()).thenReturn(true);

        assertFalse(service.canSeeAllProjects());
        verify(rbacService, never()).getUserPermsByUsername("admin");
    }

    @Test
    void save_existingProject_viewerCannotEdit() {
        loginAs("viewer");
        when(authProperties.isEnabled()).thenReturn(true);
        when(rbacService.getUserPermsByUsername("viewer")).thenReturn(Collections.emptySet());
        DnProject old = new DnProject();
        old.setId(10L);
        old.setProjectCode("demo");
        old.setProjectName("Demo");
        old.setStatus("ACTIVE");
        old.setOwner("owner");
        old.setCreatedBy("owner");
        when(projectMapper.selectById(10L)).thenReturn(old);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        DnProjectMember viewer = new DnProjectMember();
        viewer.setProjectId(10L);
        viewer.setUsername("viewer");
        viewer.setProjectRole("VIEWER");
        when(memberMapper.selectOne(any())).thenReturn(viewer);

        DnProject update = new DnProject();
        update.setId(10L);
        update.setProjectName("Changed");

        assertThrows(BusinessException.class, () -> service.save(update));
        verify(projectMapper, never()).updateById(any(DnProject.class));
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", Collections.emptyList()));
    }
}
