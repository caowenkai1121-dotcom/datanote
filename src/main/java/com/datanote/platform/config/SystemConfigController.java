package com.datanote.platform.config;

import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import com.datanote.common.model.R;
import com.datanote.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system/config")
@RequiredArgsConstructor
public class SystemConfigController {

    /** 密码脱敏占位符: 读取时回写, 保存时识别为"未改动"跳过, 避免明文回显与误覆盖 */
    private static final String PASSWORD_MASK = "******";

    private final DnSystemConfigMapper configMapper;
    private final HiveConfig hiveConfig;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    @Value("${doris.password:123456}")
    private String envDorisPassword;

    @GetMapping("/{group}")
    public R<Map<String, String>> getGroup(@PathVariable String group) {
        List<DnSystemConfig> all = configMapper.selectList(null);
        Map<String, String> result = new LinkedHashMap<>();
        String prefix = group + ".";
        for (DnSystemConfig cfg : all) {
            if (cfg.getConfigKey().startsWith(prefix)) {
                String val = cfg.getConfigValue();
                if (cfg.getConfigKey().endsWith(".password") && val != null && !val.isEmpty()) {
                    result.put(cfg.getConfigKey(), PASSWORD_MASK);
                } else {
                    result.put(cfg.getConfigKey(), val);
                }
            }
        }
        return R.ok(result);
    }

    @PostMapping
    public R<Void> save(@RequestBody Map<String, String> configs) {
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key.endsWith(".password") && PASSWORD_MASK.equals(value)) {
                continue;
            }

            if (key.endsWith(".password") && value != null && !value.isEmpty()) {
                // 未配加密密钥时拒绝保存密码类配置, 避免明文静默落库(读取时显示 ****** 制造已脱敏假象)
                if (cryptoKey == null || cryptoKey.isEmpty()) {
                    return R.fail("未配置加密密钥(datanote.crypto.key), 拒绝保存密码类配置以防明文落库");
                }
                value = CryptoUtil.encrypt(value, cryptoKey);
            }

            DnSystemConfig existing = configMapper.selectById(key);
            if (existing != null) {
                existing.setConfigValue(value);
                existing.setUpdatedAt(LocalDateTime.now());
                configMapper.updateById(existing);
            } else {
                DnSystemConfig cfg = new DnSystemConfig();
                cfg.setConfigKey(key);
                cfg.setConfigValue(value);
                cfg.setUpdatedAt(LocalDateTime.now());
                configMapper.insert(cfg);
            }
        }

        hiveConfig.reloadFromDb();
        return R.ok();
    }

    @PostMapping({"/test-doris", "/test-hive"})
    public R<String> testDoris(@RequestBody Map<String, String> params) {
        String host = params.getOrDefault("host", "38.76.183.50");
        String port = params.getOrDefault("port", "9030");
        String database = params.getOrDefault("database", "ods");
        String username = params.getOrDefault("username", "root");
        String password = resolveDorisPassword(params.get("password"));

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            return R.fail("MySQL JDBC Driver not found");
        }

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            conn.createStatement().execute("SELECT 1");
            long cost = System.currentTimeMillis() - start;
            return R.ok("Doris connection succeeded, " + cost + "ms");
        } catch (Exception e) {
            return R.fail("Doris connection failed: " + e.getMessage());
        }
    }

    private String resolveDorisPassword(String submittedPassword) {
        if (submittedPassword != null && !submittedPassword.trim().isEmpty()
                && !PASSWORD_MASK.equals(submittedPassword) && !"***".equals(submittedPassword)) {
            return submittedPassword;
        }

        DnSystemConfig cfg = configMapper.selectById("doris.password");
        if (cfg != null && cfg.getConfigValue() != null && !cfg.getConfigValue().trim().isEmpty()) {
            return CryptoUtil.decryptSafe(cfg.getConfigValue(), cryptoKey);
        }
        return envDorisPassword;
    }

    @GetMapping({"/doris-status", "/hive-status"})
    public R<Map<String, Object>> dorisStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", hiveConfig.isDorisAvailable());
        status.put("url", hiveConfig.getCurrentUrl());
        return R.ok(status);
    }
}
