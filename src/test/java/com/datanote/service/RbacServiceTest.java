package com.datanote.service;

import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 纯函数与密码哈希单测（不依赖 Spring 上下文 / 数据库）
 */
class RbacServiceTest {

    // ---------- hasPermission 纯函数 ----------

    @Test
    void wildcardGrantsEverything() {
        Set<String> perms = new HashSet<>();
        perms.add("*");
        assertTrue(RbacService.hasPermission(perms, "user:create"));
        assertTrue(RbacService.hasPermission(perms, "anything"));
    }

    @Test
    void exactMatchGranted() {
        Set<String> perms = new HashSet<>();
        perms.add("user:view");
        perms.add("role:view");
        assertTrue(RbacService.hasPermission(perms, "user:view"));
        assertTrue(RbacService.hasPermission(perms, "role:view"));
    }

    @Test
    void noMatchDenied() {
        Set<String> perms = new HashSet<>();
        perms.add("user:view");
        assertFalse(RbacService.hasPermission(perms, "user:delete"));
    }

    @Test
    void emptyOrNullDenied() {
        assertFalse(RbacService.hasPermission(Collections.emptySet(), "user:view"));
        assertFalse(RbacService.hasPermission(null, "user:view"));
        Set<String> perms = new HashSet<>();
        perms.add("*");
        assertFalse(RbacService.hasPermission(perms, null));
        assertFalse(RbacService.hasPermission(perms, ""));
    }

    // ---------- 预置 admin 密码哈希可验证 ----------

    @Test
    void presetAdminHashMatchesAdmin123() {
        // 与 sql/36_rbac.sql 中预置的 admin 哈希一致，密码为 admin123
        String presetHash = "$2a$10$d3uzf5P/igk82XIDPUfTPeV6kLEceqlq18A2aSbocwl/FU1dKjZsm";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("admin123", presetHash));
        assertFalse(encoder.matches("wrong-password", presetHash));
    }

    @Test
    void encodedPasswordIsNotPlaintextAndVerifiable() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String encoded = encoder.encode("secret");
        assertFalse("secret".equals(encoded));
        assertTrue(encoder.matches("secret", encoded));
    }
}
