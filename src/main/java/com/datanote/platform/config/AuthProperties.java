package com.datanote.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "datanote.auth")
public class AuthProperties {

    /** 是否启用登录认证，默认开启。 */
    private boolean enabled = true;

    /** 登录用户名 */
    private String username = "admin";

    /** 内存兜底账号密码；为空时不创建可猜测的兜底密码。 */
    private String password = "";

    /**
     * 判断是否启用认证（仅显式 enabled=false 时关闭）
     */
    public boolean isEnabled() {
        return enabled;
    }
}
