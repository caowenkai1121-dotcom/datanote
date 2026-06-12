package com.datanote.platform.iam;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户 — 全站统一取用户名入口(写入 createdBy/owner/操作人时使用)。
 * 未启用认证或未登录时返回 "anonymous", 永不抛异常。
 */
public final class CurrentUserUtil {

    private CurrentUserUtil() {}

    public static String currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return auth.getName();
            }
        } catch (Exception ignore) {
            // 安全上下文不可用时按匿名处理
        }
        return "anonymous";
    }
}
