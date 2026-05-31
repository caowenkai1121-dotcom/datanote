package com.datanote.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PM-M2：项目角色权限矩阵纯函数。 */
public class ProjectRolesTest {

    @Test
    void validRoles() {
        assertTrue(ProjectRoles.isValid("OWNER"));
        assertTrue(ProjectRoles.isValid("VIEWER"));
        assertFalse(ProjectRoles.isValid("ROOT"));
        assertFalse(ProjectRoles.isValid(null));
    }

    @Test
    void ownerHasAll() {
        assertTrue(ProjectRoles.can("OWNER", "release:approve"));
        assertTrue(ProjectRoles.can("OWNER", "member:manage"));
        assertTrue(ProjectRoles.can("OWNER", "read"));
    }

    @Test
    void viewerReadOnly() {
        assertTrue(ProjectRoles.can("VIEWER", "read"));
        assertFalse(ProjectRoles.can("VIEWER", "asset:manage"));
        assertFalse(ProjectRoles.can("VIEWER", "release:submit"));
    }

    @Test
    void developerNoApprove() {
        assertTrue(ProjectRoles.can("DEVELOPER", "asset:manage"));
        assertTrue(ProjectRoles.can("DEVELOPER", "release:submit"));
        assertFalse(ProjectRoles.can("DEVELOPER", "release:approve"));
        assertFalse(ProjectRoles.can("DEVELOPER", "member:manage"));
    }

    @Test
    void opsRunReadOnly() {
        assertTrue(ProjectRoles.can("OPS", "run"));
        assertTrue(ProjectRoles.can("OPS", "read"));
        assertFalse(ProjectRoles.can("OPS", "asset:manage"));
    }

    @Test
    void matrixCoversAllRoles() {
        assertEquals(5, ProjectRoles.matrix().size());
        assertEquals("负责人", ProjectRoles.label("OWNER"));
    }
}
