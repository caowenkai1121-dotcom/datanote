package com.datanote.platform.iam;

import com.datanote.platform.config.AuthProperties;
import com.datanote.common.model.R;
import com.datanote.platform.iam.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 认证控制器 — 登录、登出、状态查询
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthProperties authProperties;
    private final RbacService rbacService;
    private final LoginAttemptService loginAttemptService;
    private final com.datanote.platform.audit.AuditService auditService;

    /**
     * 登录
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (!authProperties.isEnabled()) {
            return R.fail(R.CODE_BAD_REQUEST, "认证未启用");
        }

        String username = request.getUsername();
        String ip = clientIp(httpRequest);

        // 防暴力破解: 锁定中直接拒绝, 不消耗认证
        long lockedSec = loginAttemptService.lockedRemainingSeconds(username);
        if (lockedSec > 0) {
            auditService.record(username, "LOGIN_LOCKED", "POST", "/api/auth/login", ip, 423,
                    "账号锁定中, 剩余 " + lockedSec + "s");
            return R.fail(R.CODE_FORBIDDEN, "账号已锁定, 请 " + ((lockedSec + 59) / 60) + " 分钟后再试");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.getPassword())
            );

            // 认证成功: 清失败计数 + 换 SessionId 防会话固定 + 建 Session
            loginAttemptService.recordSuccess(username);
            HttpSession old = httpRequest.getSession(false);
            if (old != null) old.invalidate();   // 防会话固定攻击: 登录后换新 session
            SecurityContextHolder.getContext().setAuthentication(authentication);
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            rbacService.touchLastLogin(authentication.getName());
            auditService.record(authentication.getName(), "LOGIN", "POST", "/api/auth/login", ip, 200, "登录成功");

            Map<String, Object> data = new HashMap<>();
            data.put("username", authentication.getName());
            data.put("perms", resolvePerms(authentication));
            return R.ok(data);
        } catch (AuthenticationException e) {
            boolean nowLocked = loginAttemptService.recordFailure(username);
            int left = loginAttemptService.remainingAttempts(username);
            auditService.record(username, "LOGIN_FAIL", "POST", "/api/auth/login", ip, 401,
                    "登录失败" + (nowLocked ? "(触发锁定)" : ", 剩余尝试 " + left + " 次"));
            if (nowLocked) {
                long sec = loginAttemptService.lockedRemainingSeconds(username);
                return R.fail(R.CODE_FORBIDDEN, "密码错误次数过多, 账号已锁定 " + ((sec + 59) / 60) + " 分钟");
            }
            return R.fail(R.CODE_UNAUTHORIZED, "用户名或密码错误" + (left <= 2 ? "(还可尝试 " + left + " 次后锁定)" : ""));
        }
    }

    /** 客户端 IP: 优先 X-Forwarded-For 首段。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int c = xff.indexOf(',');
            return c > 0 ? xff.substring(0, c).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 查询登录状态
     */
    @Operation(summary = "查询登录状态")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("authEnabled", authProperties.isEnabled());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());

        data.put("authenticated", authenticated);
        if (authenticated) {
            data.put("username", authentication.getName());
            data.put("perms", resolvePerms(authentication));
            data.put("mustChangePwd", rbacService.mustChangePwd(authentication.getName()));
        }
        return R.ok(data);
    }

    /**
     * 解析当前认证主体的权限集：优先查 dn_user；查不到则按 authorities 判断 admin（内存兜底）。
     */
    private Set<String> resolvePerms(Authentication authentication) {
        Set<String> perms;
        try {
            perms = rbacService.getUserPermsByUsername(authentication.getName());
        } catch (Exception e) {
            // dn_user 表未建 / 查询异常时降级，避免登录/状态接口 500
            perms = new HashSet<>();
        }
        // 内存兜底 admin('*') 仅对"不在 dn_user 表中的同名引导账号"生效;
        // dn_user 中真实存在但无角色的同名账号不再白拿超管(原逻辑的提权漏洞)。
        if (perms.isEmpty() && !rbacService.existsInDb(authentication.getName())) {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> "ROLE_ADMIN".equals(a) || "*".equals(a));
            if (isAdmin) {
                perms = new HashSet<>();
                perms.add("*");
            }
        }
        return perms;
    }

    /**
     * 自助修改密码(校验原密码)。
     */
    @Operation(summary = "修改当前用户密码")
    @PostMapping("/change-password")
    public R<String> changePassword(@RequestBody Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        if (!authenticated) {
            return R.fail(R.CODE_UNAUTHORIZED, "未登录");
        }
        rbacService.changePassword(auth.getName(),
                body == null ? null : body.get("oldPassword"),
                body == null ? null : body.get("newPassword"));
        return R.ok("密码已修改, 下次登录请使用新密码");
    }

    /**
     * 注销
     */
    @Operation(summary = "用户注销")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return R.ok();
    }

    /**
     * 登录请求体
     */
    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
