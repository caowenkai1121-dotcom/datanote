package com.datanote.platform.config;

import com.datanote.config.HiveConfig;
import com.datanote.mapper.DnSystemConfigMapper;
import com.datanote.model.DnSystemConfig;
import com.datanote.model.R;
import com.datanote.util.CryptoUtil;
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
                    result.put(cfg.getConfigKey(), "******");
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

            if (key.endsWith(".password") && "******".equals(value)) {
                continue;
            }

            if (key.endsWith(".password") && value != null && !value.isEmpty()
                    && cryptoKey != null && !cryptoKey.isEmpty()) {
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
                && !"******".equals(submittedPassword) && !"***".equals(submittedPassword)) {
            return submittedPassword;
        }

        DnSystemConfig cfg = configMapper.selectById("doris.password");
        if (cfg != null && cfg.getConfigValue() != null && !cfg.getConfigValue().trim().isEmpty()) {
            return CryptoUtil.decryptSafe(cfg.getConfigValue(), cryptoKey);
        }
        return envDorisPassword;
    }

    @PostMapping("/test-hdfs")
    public R<String> testHdfs(@RequestBody Map<String, String> params) {
        return R.fail("HDFS is no longer required after switching the warehouse engine to Doris.");
    }

    @GetMapping({"/doris-status", "/hive-status"})
    public R<Map<String, Object>> dorisStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", hiveConfig.isDorisAvailable());
        status.put("url", hiveConfig.getCurrentUrl());
        return R.ok(status);
    }
}
