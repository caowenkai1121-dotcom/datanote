package com.datanote.platform.config;

import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import com.datanote.common.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Slf4j
@Configuration
public class HiveConfig {

    @Value("${doris.url:jdbc:mysql://38.76.183.50:9030/ods?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true}")
    private String envDorisUrl;

    @Value("${doris.username:root}")
    private String envDorisUsername;

    @Value("${doris.password:123456}")
    private String envDorisPassword;

    @Value("${hive.url:}")
    private String legacyHiveUrl;

    @Value("${hive.username:}")
    private String legacyHiveUsername;

    @Value("${hive.password:}")
    private String legacyHivePassword;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    @Autowired(required = false)
    private DnSystemConfigMapper systemConfigMapper;

    private volatile HikariDataSource dorisDataSource;
    private volatile boolean dorisAvailable = false;
    private volatile String currentUrl = "";

    @PostConstruct
    public void init() {
        reloadFromDb();
    }

    public void reloadFromDb() {
        closeDataSource();

        String url = firstNonBlank(getDbConfig("doris.url"), getDbConfig("hive.url"), envDorisUrl, legacyHiveUrl);
        String username = firstNonBlank(getDbConfig("doris.username"), getDbConfig("hive.username"),
                envDorisUsername, legacyHiveUsername);
        String password = firstNonBlank(getDbConfigDecrypt("doris.password"), getDbConfigDecrypt("hive.password"),
                envDorisPassword, legacyHivePassword);

        initDataSource(url, username, password);
    }

    private void closeDataSource() {
        if (dorisDataSource != null) {
            try {
                dorisDataSource.close();
                log.info("Doris connection pool closed");
            } catch (Exception e) {
                log.warn("Failed to close Doris connection pool: {}", e.getMessage());
            }
            dorisDataSource = null;
            dorisAvailable = false;
        }
    }

    private void initDataSource(String url, String username, String password) {
        currentUrl = url != null ? url : "";

        if (url == null || url.isEmpty()) {
            log.warn("Doris URL is not configured");
            return;
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            log.warn("MySQL JDBC Driver not found; Doris features are unavailable");
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username != null ? username : "");
        config.setPassword(password != null ? password : "");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(580000);
        config.setKeepaliveTime(120000);
        config.setPoolName("DorisHikariPool");
        config.setConnectionTestQuery("SELECT 1");
        config.setInitializationFailTimeout(-1);

        try {
            dorisDataSource = new HikariDataSource(config);
            dorisAvailable = true;
            log.info("Doris connection pool initialized: {}", url);
        } catch (Exception e) {
            log.warn("Doris connection pool initialization failed: {}", e.getMessage());
        }
    }

    public boolean isHiveAvailable() {
        return isDorisAvailable();
    }

    public boolean isDorisAvailable() {
        return dorisAvailable && dorisDataSource != null;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource ds = dorisDataSource; // 先读入局部变量, 避免与 reload 置 null 竞态
        if (ds == null) {
            throw new SQLException("Doris is not connected. Configure the Doris connection in System Settings.");
        }
        return ds.getConnection();
    }

    public Connection getRawConnection() throws SQLException {
        if (currentUrl == null || currentUrl.isEmpty()) {
            throw new SQLException("Doris is not configured.");
        }
        String username = firstNonBlank(getDbConfig("doris.username"), getDbConfig("hive.username"),
                envDorisUsername, legacyHiveUsername);
        String password = firstNonBlank(getDbConfigDecrypt("doris.password"), getDbConfigDecrypt("hive.password"),
                envDorisPassword, legacyHivePassword);
        return DriverManager.getConnection(currentUrl, username, password);
    }

    private String getDbConfig(String key) {
        if (systemConfigMapper == null) return null;
        try {
            DnSystemConfig cfg = systemConfigMapper.selectById(key);
            return cfg != null ? cfg.getConfigValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getDbConfigDecrypt(String key) {
        String val = getDbConfig(key);
        if (val != null && !val.isEmpty() && cryptoKey != null && !cryptoKey.isEmpty()) {
            String decrypted = CryptoUtil.decryptSafe(val, cryptoKey);
            return decrypted != null ? decrypted : val;
        }
        return val;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
