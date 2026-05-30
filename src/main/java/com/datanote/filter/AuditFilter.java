package com.datanote.filter;

import com.datanote.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 全局审计 Filter（M12）—— @Component + OncePerRequestFilter 由 Spring Boot 自动注册，
 * 无需改 WebMvcConfig。对变更类请求(POST/PUT/DELETE) /api/** 记录一条审计。
 *
 * 零侵入约束：先放行业务，finally 中异步无感记录；整段 try/catch，审计任何异常都不影响主请求。
 * 排除审计自身路径避免递归（见 AuditService.shouldAudit）。
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
public class AuditFilter extends OncePerRequestFilter {

    private final AuditService auditService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                String method = request.getMethod();
                String path = request.getRequestURI();
                if (AuditService.shouldAudit(method, path)) {
                    long cost = System.currentTimeMillis() - start;
                    auditService.record(currentUser(), AuditService.classify(method, path),
                            method, path, clientIp(request), response.getStatus(), "耗时" + cost + "ms");
                }
            } catch (Exception e) {
                // 审计失败绝不阻断业务
                log.warn("审计 Filter 记录失败", e);
            }
        }
    }

    /** 当前登录用户名；匿名/未认证记 anonymous。 */
    private String currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return auth.getName();
            }
        } catch (Exception ignore) {
            // 取不到身份按匿名处理
        }
        return "anonymous";
    }

    /** 客户端 IP：优先 X-Forwarded-For 首段，否则 remoteAddr。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
