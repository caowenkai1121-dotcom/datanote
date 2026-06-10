package com.datanote.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 — 根路径重定向到 workspace
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/workspace.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // SPA 壳(*.html)无 ?v= 版本参数, 浏览器缓存旧壳会配错新版本 js/css —— 强制 no-cache 每次回源校验;
        // js/css 走 ?v= 版本化, 仍由默认静态处理器长缓存, 不受影响
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }
}
