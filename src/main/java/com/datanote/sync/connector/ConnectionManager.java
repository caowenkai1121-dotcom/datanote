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
 * 按数据源 ID 缓存 HikariCP 连接池（MySQL 协议族）。
 */
@Slf4j
@Component
public class ConnectionManager {

    private final DnDatasourceMapper datasourceMapper;

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    @Value("${datanote.sync.pool-max-size:5}")
    private int poolMaxSize;

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

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

    /** 获取（或懒建）指定数据源的连接，调用方负责 close（归还池）。 */
    public Connection getConnection(Long datasourceId, String db) throws SQLException {
        HikariDataSource pool = pools.computeIfAbsent(datasourceId, k -> createPool(datasourceId, db));
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
        cfg.setPoolName("sync-ds-" + datasourceId);
        log.info("创建同步连接池: dsId={}, url={}", datasourceId, url);
        return new HikariDataSource(cfg);
    }

    /** 数据源配置变更/删除时调用，关闭并移除池。 */
    public void evict(Long datasourceId) {
        HikariDataSource pool = pools.remove(datasourceId);
        if (pool != null) {
            pool.close();
            log.info("关闭同步连接池: dsId={}", datasourceId);
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
