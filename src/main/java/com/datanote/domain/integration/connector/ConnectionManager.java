package com.datanote.domain.integration.connector;

import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.common.util.CryptoUtil;
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
        // 批量重写：让 addBatch/executeBatch 真正合并为单次往返（否则驱动逐条发送，批处理形同虚设）。
        // 放在固定串内、extraParams 之前，用户仍可在 extraParams 覆盖（MySQL 取后者）。
        url.append("&rewriteBatchedStatements=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048");
        if (extraParams != null && !extraParams.isEmpty()) {
            url.append("&").append(extraParams);
        }
        return url.toString();
    }

    /** 构建 PostgreSQL jdbcUrl（DS-M8，可单测）。db=PG 数据库名（非 schema）。 */
    public static String buildPgUrl(String host, Integer port, String db, String extraParams) {
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(host).append(":").append(port).append("/");
        if (db != null && !db.isEmpty()) {
            url.append(db);
        }
        if (extraParams != null && !extraParams.isEmpty()) {
            url.append("?").append(extraParams);
        }
        return url.toString();
    }

    /** 构建 SQL Server jdbcUrl（DS-M9，可单测）。db=SQLServer 数据库名（非 schema）。 */
    public static String buildSqlServerUrl(String host, Integer port, String db, String extraParams) {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://").append(host).append(":").append(port).append(";");
        if (db != null && !db.isEmpty()) {
            url.append("databaseName=").append(db).append(";");
        }
        url.append("encrypt=false;trustServerCertificate=true;loginTimeout=10");
        if (extraParams != null && !extraParams.isEmpty()) {
            url.append(";").append(extraParams);
        }
        return url.toString();
    }

    /** 构建 Oracle jdbcUrl（DS-M9，可单测）。db=服务名（如 XEPDB1）。 */
    public static String buildOracleUrl(String host, Integer port, String db) {
        String svc = (db == null || db.isEmpty()) ? "XEPDB1" : db;
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + svc;
    }

    private static String poolKey(Long datasourceId, String db) {
        return datasourceId + ":" + (db == null ? "" : db);
    }

    /** DS-M4：解析 Stream Load HTTP 端点（端口默认 8030，extraParams 可含 dorisHttpPort=NNNN 覆盖）。 */
    public StreamLoadTarget resolveStreamLoadTarget(Long datasourceId) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String pwd = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        int httpPort = parseHttpPort(ds.getExtraParams(), 8030);
        return new StreamLoadTarget(ds.getHost(), httpPort, ds.getUsername(), pwd);
    }

    /** 从 extraParams(JDBC 参数串)解析 dorisHttpPort，缺省返回 def。可单测。 */
    public static int parseHttpPort(String extraParams, int def) {
        if (extraParams == null) return def;
        for (String kv : extraParams.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).trim().equalsIgnoreCase("dorisHttpPort")) {
                try { return Integer.parseInt(kv.substring(i + 1).trim()); } catch (NumberFormatException ignore) {}
            }
        }
        return def;
    }

    /** 获取（或懒建）指定数据源+库的连接，调用方负责 close（归还池）。 */
    public Connection getConnection(Long datasourceId, String db) throws SQLException {
        HikariDataSource pool = pools.computeIfAbsent(poolKey(datasourceId, db), k -> createPool(datasourceId, db));
        return pool.getConnection();
    }

    /** 归一化数据源 type：trim + 大写，空/缺省按 MySQL 处理。统一识别口径，收口边界。 */
    private static String normalizeType(String raw) {
        if (raw == null) return "MYSQL";
        String t = raw.trim().toUpperCase();
        return t.isEmpty() ? "MYSQL" : t;
    }

    private HikariDataSource createPool(Long datasourceId, String db) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String pwd = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        // DS-M8：按数据源 type 选驱动/URL（PG 连接库取 databaseName，同步「db」为 schema 不入 URL）
        String type = normalizeType(ds.getType());
        boolean pg = "POSTGRESQL".equals(type) || "POSTGRES".equals(type) || "PG".equals(type);
        boolean mssql = "SQLSERVER".equals(type) || "MSSQL".equals(type) || "SQL_SERVER".equals(type);
        boolean oracle = "ORACLE".equals(type);
        String url;
        String driver;
        if (pg) {
            url = buildPgUrl(ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getExtraParams());
            driver = "org.postgresql.Driver";
        } else if (mssql) {
            url = buildSqlServerUrl(ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getExtraParams());
            driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        } else if (oracle) {
            url = buildOracleUrl(ds.getHost(), ds.getPort(), ds.getDatabaseName());
            driver = "oracle.jdbc.OracleDriver";
        } else {
            url = buildJdbcUrl(ds.getHost(), ds.getPort(), db != null ? db : ds.getDatabaseName(), ds.getExtraParams());
            driver = "com.mysql.cj.jdbc.Driver";
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(pwd);
        cfg.setDriverClassName(driver);
        cfg.setMaximumPoolSize(poolMaxSize);
        cfg.setMinimumIdle(Math.min(2, poolMaxSize));
        cfg.setConnectionTimeout(10000);
        // 空闲连接保活与回收：避免被 DB 端 wait_timeout 静默断开后，下次取连接才暴露失败。
        cfg.setMaxLifetime(25 * 60 * 1000L);   // < 典型 wait_timeout，留余量主动重建
        cfg.setKeepaliveTime(5 * 60 * 1000L);  // 周期性保活探测
        cfg.setIdleTimeout(10 * 60 * 1000L);
        cfg.setValidationTimeout(5000);
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
