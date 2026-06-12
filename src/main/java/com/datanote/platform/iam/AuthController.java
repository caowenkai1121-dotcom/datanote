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

    /**
     * 登录
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (!authProperties.isEnabled()) {
            return R.fail(R.CODE_BAD_REQUEST, "认证未启用");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            // 认证成功，创建 Session
            SecurityContextHolder.getContext().setAuthentication(authentication);
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            Map<String, Object> data = new HashMap<>();
            data.put("username", authentication.getName());
            data.put("perms", resolvePerms(authentication));
            return R.ok(data);
        } catch (AuthenticationException e) {
            return R.fail(R.CODE_UNAUTHORIZED, "用户名或密码错误");
        }
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
        if (perms.isEmpty()) {
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
