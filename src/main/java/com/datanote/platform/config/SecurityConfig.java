package com.datanote.platform.config;

import com.alibaba.fastjson.JSON;
import com.datanote.common.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.util.Arrays;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

/**
 * Spring Security 配置
 * <p>
 * datanote.auth.enabled 默认开启；仅显式设为 false 时放行所有请求。
 * 启用时，/api/** 和 WebSocket 握手需要认证，静态资源和 Swagger 等放行。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final DbUserDetailsService dbUserDetailsService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器：双数据源 provider。
     * <p>
     * 1) DB provider（dn_user 表）优先匹配；hideUserNotFoundExceptions=false 使得
     *    用户在 dn_user 中找不到 / 表不存在时抛出 UsernameNotFoundException，
     *    ProviderManager 会继续尝试下一个 provider，从而落到内存兜底 admin；
     * 2) 内存 provider（配置 admin）兜底，保证即使 dn_user 为空/未建表，
     *    配置里的 admin 仍能登录、不被锁死。
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        PasswordEncoder encoder = passwordEncoder();

        // DB provider（dn_user 表）：优先匹配；找不到用户时不隐藏异常，便于回退到内存兜底
        DaoAuthenticationProvider dbProvider = new DaoAuthenticationProvider();
        dbProvider.setUserDetailsService(dbUserDetailsService);
        dbProvider.setPasswordEncoder(encoder);
        dbProvider.setHideUserNotFoundExceptions(false);

        // 内存兜底 provider（沿用原配置 admin），保证 dn_user 为空/未建表时仍可登录、不锁死
        String fallbackPassword = authProperties.getPassword();
        if (fallbackPassword == null || fallbackPassword.trim().isEmpty()) {
            fallbackPassword = UUID.randomUUID().toString();
        }
        InMemoryUserDetailsManager inMemory = new InMemoryUserDetailsManager(
                User.withUsername(authProperties.getUsername())
                        .password(encoder.encode(fallbackPassword))
                        .roles("ADMIN")
                        .build());
        DaoAuthenticationProvider memProvider = new DaoAuthenticationProvider();
        memProvider.setUserDetailsService(inMemory);
        memProvider.setPasswordEncoder(encoder);

        return new ProviderManager(Arrays.asList(dbProvider, memProvider));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CORS：委托给已有的 CorsFilter Bean
        http.cors();

        if (!authProperties.isEnabled()) {
            // 开放模式: 放行所有请求, 同时关 CSRF(无会话语义)
            http.csrf().disable();
            http.authorizeRequests().anyRequest().permitAll();
        } else {
            // CSRF(P1): Session+Cookie 认证下启用。CookieCsrfTokenRepository 下发可读 XSRF-TOKEN cookie,
            // 前端 fetch 包装读 cookie 发 X-XSRF-TOKEN 头。放行: 登录(尚无 token)、WebSocket/SockJS(xhr_send 走 POST 传输)。
            http.csrf()
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringAntMatchers("/api/auth/login", "/ws/**");
            // 密码非空 → 启用认证
            http.authorizeRequests()
                    // 静态资源
                    .antMatchers("/*.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                    // Swagger / OpenAPI
                    .antMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
                    // WebSocket
                    .antMatchers("/ws/**").authenticated()
                    // 认证接口
                    .antMatchers("/api/auth/login", "/api/auth/status").permitAll()
                    // 当前用户探测接口（未登录返回匿名信息，便于前端判断登录态）
                    .antMatchers("/api/rbac/me").permitAll()
                    // 根路径重定向
                    .antMatchers("/").permitAll()
                    // 其他 API 需要认证
                    .antMatchers("/api/**").authenticated()
                    // 其他资源放行
                    .anyRequest().permitAll();

            // 未认证时返回 JSON 401（不重定向到登录页）
            http.exceptionHandling()
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        R<Object> result = R.fail(R.CODE_UNAUTHORIZED, "未登录或登录已过期");
                        response.getWriter().write(JSON.toJSONString(result));
                    });
        }

        // 启用 Session 管理（默认行为，显式声明确保清晰）
        http.sessionManagement();

        // 禁用默认登录表单和 HTTP Basic
        http.formLogin().disable();
        http.httpBasic().disable();

        return http.build();
    }
}
