package com.datanote.sync.connector;

import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.model.DnDatasource;
import com.datanote.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (数据源ID, 库名) 缓存 HikariCP 连接池（MySQL 协议族）。
 * 同一数据源的不同库各自独立建池，确保连接默认库正确、不依赖跨库权限。
 */
@Slf4j
@Component
public class ConnectionManager {

    private final DnDatasourceMapper datasourceMapper;

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    @Value("${datanote.sync.pool-max-size:5}")
    private int poolMaxSize;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public ConnectionManager(DnDatasourceMapper datasourceMapper) {
        this.datasourceMapper = datasourceMapper;
    }

    /** 构建 MySQL 协议 jdbcUrl（可单测） */
    public static String buildJdbcUrl(String host, Integer port, String db, String extraParams) {
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(":").append(port).append("/");
        if (db != null && !db.isEmpty()) {
            url.append(db);
        }
        url.append("?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true");
        if (extraParams != null && !extraParams.isEmpty()) {
            url.append("&").append(extraParams);
        }
        return url.toString();
    }

    private static String poolKey(Long datasourceId, String db) {
        return datasourceId + ":" + (db == null ? "" : db);
    }

    /** 获取（或懒建）指定数据源+库的连接，调用方负责 close（归还池）。 */
    public Connection getConnection(Long datasourceId, String db) throws SQLException {
        HikariDataSource pool = pools.computeIfAbsent(poolKey(datasourceId, db), k -> createPool(datasourceId, db));
        return pool.getConnection();
    }

    private HikariDataSource createPool(Long datasourceId, String db) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String pwd = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = buildJdbcUrl(ds.getHost(), ds.getPort(),
                db != null ? db : ds.getDatabaseName(), ds.getExtraParams());

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(pwd);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(poolMaxSize);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10000);
        cfg.setPoolName("sync-ds-" + datasourceId + (db == null ? "" : "-" + db));
        log.info("创建同步连接池: dsId={}, db={}, url={}", datasourceId, db, url);
        return new HikariDataSource(cfg);
    }

    /** 数据源配置变更/删除时调用，关闭并移除该数据源的所有库的池。 */
    public void evict(Long datasourceId) {
        String prefix = datasourceId + ":";
        pools.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().close();
                log.info("关闭同步连接池: {}", e.getKey());
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
