package com.datanote.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 跨域配置 — 仅放行白名单来源的跨域请求
 * <p>
 * 同时暴露 CorsConfigurationSource Bean，供 Spring Security 的 http.cors() 使用，
 * 避免 Security 过滤链与 MVC CORS 配置冲突。
 * <p>
 * 安全: 禁止 "通配来源(*) + allowCredentials(true)" 组合(任意站点可携会话凭据跨域)。
 * 本应用为同源单体, 同源请求不触发 CORS; 如确需跨域前端, 用
 * {@code datanote.cors.allowed-origins} 显式枚举可信域名(逗号分隔), 默认为空=不放行任何跨域。
 */
@Configuration
public class CorsConfig {

    /** 可信跨域来源白名单(逗号分隔), 默认空 —— 同源单体无需跨域。 */
    @Value("${datanote.cors.allowed-origins:}")
    private String allowedOrigins;

    /**
     * CORS 配置源 — 同时被 Spring MVC 和 Spring Security 使用
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 显式白名单代替通配 "*", 才能与 allowCredentials(true) 安全共存
        java.util.List<String> origins = (allowedOrigins == null || allowedOrigins.trim().isEmpty())
                ? java.util.Collections.emptyList()
                : Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        config.setAllowedOrigins(origins);
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
