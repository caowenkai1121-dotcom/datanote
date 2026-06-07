package com.datanote.domain.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.datanote.model.ColumnInfo;
import com.datanote.util.DorisSqlUtil;
import com.datanote.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

@Service
public class DataxService {

    private static final Logger log = LoggerFactory.getLogger(DataxService.class);

    @Value("${datax.home}")
    private String dataxHome;

    @Value("${datax.jvm}")
    private String dataxJvm;

    @Value("${datax.job-dir}")
    private String jobDir;

    @Value("${datax.mode:local}")
    private String dataxMode;

    @Value("${doris.host:38.76.183.50}")
    private String dorisHost;

    @Value("${doris.query-port:9030}")
    private int dorisQueryPort;

    @Value("${doris.database:ods}")
    private String dorisDatabase;

    @Value("${doris.username:root}")
    private String dorisUsername;

    @Value("${doris.password:123456}")
    private String dorisPassword;

    private String translateHost(String host) {
        if ("docker".equals(dataxMode)) {
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                return "host.docker.internal";
            }
        }
        return host;
    }

    public String generateJobJson(String mysqlHost, int mysqlPort, String mysqlUser, String mysqlPassword,
                                  String sourceDb, String sourceTable,
                                  String odsTable, List<ColumnInfo> columns) throws IOException {
        String bizdate = java.time.LocalDate.now().minusDays(1).toString();
        return generateJobJson(mysqlHost, mysqlPort, mysqlUser, mysqlPassword,
                sourceDb, sourceTable, odsTable, columns, bizdate);
    }

    public String generateJobJson(String mysqlHost, int mysqlPort, String mysqlUser, String mysqlPassword,
                                  String sourceDb, String sourceTable,
                                  String odsTable, List<ColumnInfo> columns,
                                  String bizdate) throws IOException {
        new File(jobDir).mkdirs();

        String actualHost = translateHost(mysqlHost);
        List<String> dorisColumns = DorisSqlUtil.toDorisColumnNames(columns);

        JSONObject job = new JSONObject(true);
        JSONObject jobContent = new JSONObject(true);

        JSONObject reader = new JSONObject(true);
        reader.put("name", "mysqlreader");
        JSONObject readerParam = new JSONObject(true);
        readerParam.put("username", mysqlUser);
        readerParam.put("password", mysqlPassword);

        JSONArray readerConnections = new JSONArray();
        JSONObject readerConnection = new JSONObject(true);
        readerConnection.put("jdbcUrl", new JSONArray() {{
            add("jdbc:mysql://" + actualHost + ":" + mysqlPort + "/" + sourceDb
                    + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true");
        }});
        readerConnection.put("querySql", new JSONArray() {{
            add(buildSourceQuery(sourceDb, sourceTable, columns, dorisColumns, bizdate));
        }});
        readerConnections.add(readerConnection);
        readerParam.put("connection", readerConnections);
        reader.put("parameter", readerParam);

        JSONObject writer = new JSONObject(true);
        writer.put("name", "mysqlwriter");
        JSONObject writerParam = new JSONObject(true);
        writerParam.put("username", dorisUsername);
        writerParam.put("password", dorisPassword);
        writerParam.put("writeMode", "insert");
        writerParam.put("batchSize", 2048);

        JSONArray writerColumns = new JSONArray();
        writerColumns.add("dt");
        for (String columnName : dorisColumns) {
            writerColumns.add(columnName);
        }
        writerParam.put("column", writerColumns);

        writerParam.put("preSql", new JSONArray() {{
            add("DELETE FROM " + DorisSqlUtil.quoteQualified(dorisDatabase, odsTable)
                    + " WHERE `dt` = '" + DorisSqlUtil.escapeSqlLiteral(bizdate) + "'");
        }});

        JSONArray writerConnections = new JSONArray();
        JSONObject writerConnection = new JSONObject(true);
        writerConnection.put("jdbcUrl", "jdbc:mysql://" + dorisHost + ":" + dorisQueryPort + "/" + dorisDatabase
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true");
        writerConnection.put("table", new JSONArray() {{ add(odsTable); }});
        writerConnections.add(writerConnection);
        writerParam.put("connection", writerConnections);
        writer.put("parameter", writerParam);

        JSONArray contentArr = new JSONArray();
        JSONObject contentItem = new JSONObject(true);
        contentItem.put("reader", reader);
        contentItem.put("writer", writer);
        contentArr.add(contentItem);

        JSONObject setting = new JSONObject(true);
        JSONObject speed = new JSONObject(true);
        speed.put("channel", 3);
        setting.put("speed", speed);

        jobContent.put("content", contentArr);
        jobContent.put("setting", setting);
        job.put("job", jobContent);

        String jsonStr = JSON.toJSONString(job, true);
        String filePath = jobDir + "/" + odsTable + ".json";
        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(jsonStr);
        }
        log.info("DataX JSON generated: {}", filePath);
        return filePath;
    }

    private String buildSourceQuery(String sourceDb, String sourceTable, List<ColumnInfo> columns,
                                    List<String> dorisColumns, String bizdate) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT '").append(DorisSqlUtil.escapeSqlLiteral(bizdate)).append("' AS `dt`");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(", ")
                    .append(DorisSqlUtil.quoteIdentifier(columns.get(i).getName()))
                    .append(" AS ")
                    .append(DorisSqlUtil.quoteIdentifier(dorisColumns.get(i)));
        }
        sql.append(" FROM ").append(DorisSqlUtil.quoteQualified(sourceDb, sourceTable));
        return sql.toString();
    }

    public String generateJobJsonString(String mysqlHost, int mysqlPort, String mysqlUser, String mysqlPassword,
                                        String sourceDb, String sourceTable,
                                        String odsTable, List<ColumnInfo> columns) {
        try {
            String filePath = generateJobJson(mysqlHost, mysqlPort, mysqlUser, mysqlPassword,
                    sourceDb, sourceTable, odsTable, columns);
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        } catch (Exception e) {
            log.error("Generate DataX JSON failed", e);
            return null;
        }
    }

    public ProcessUtil.ExecResult runJob(String jobFilePath) throws Exception {
        if ("docker".equals(dataxMode)) {
            return runJobDocker(jobFilePath);
        }
        return runJobLocal(jobFilePath);
    }

    private ProcessUtil.ExecResult runJobLocal(String jobFilePath) throws Exception {
        String classpath = dataxHome + "/lib/*";
        String[] cmd = {
                "java", "-server",
                "-Xms1g", "-Xmx1g",
                "-Ddatax.home=" + dataxHome,
                "-classpath", classpath,
                "com.alibaba.datax.core.Engine",
                "-mode", "standalone",
                "-jobid", "-1",
                "-job", jobFilePath
        };
        log.info("Run DataX (local): {}", jobFilePath);
        return ProcessUtil.exec(cmd, 600);
    }

    private ProcessUtil.ExecResult runJobDocker(String jobFilePath) throws Exception {
        String containerName = "datanote-datax";
        String containerJobPath = "/tmp/datax_jobs/" + new File(jobFilePath).getName();

        String[] cpCmd = {"docker", "cp", jobFilePath, containerName + ":" + containerJobPath};
        ProcessUtil.exec(cpCmd, 30);

        String[] cmd = {
                "docker", "exec", containerName,
                "python", "/opt/datax/bin/datax.py", containerJobPath
        };
        log.info("Run DataX (docker): {} -> {}", jobFilePath, containerJobPath);
        return ProcessUtil.exec(cmd, 600);
    }

    public ProcessUtil.ExecResult runJobFromJson(String dataxJsonContent, String taskName) throws Exception {
        if ("docker".equals(dataxMode)) {
            dataxJsonContent = dataxJsonContent
                    .replace("jdbc:mysql://127.0.0.1", "jdbc:mysql://host.docker.internal")
                    .replace("jdbc:mysql://localhost", "jdbc:mysql://host.docker.internal");
        }

        new File(jobDir).mkdirs();
        String tmpFile = jobDir + "/" + taskName + "_" + System.currentTimeMillis() + ".json";
        try {
            try (FileWriter fw = new FileWriter(tmpFile)) {
                fw.write(dataxJsonContent);
            }
            return runJob(tmpFile);
        } finally {
            try {
                new java.io.File(tmpFile).delete();
            } catch (Exception ignored) {
            }
        }
    }
}
