package com.datanote.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.datanote.model.ColumnInfo;
import com.datanote.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DataX 同步服务 — 生成 DataX JSON 配置并执行同步任务
 */
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

    @Value("${hive.default-fs}")
    private String hiveDefaultFs;

    @Value("${hive.warehouse}")
    private String hiveWarehouse;

    /**
     * Docker 模式下，将 127.0.0.1/localhost 替换为 host.docker.internal
     * 使容器内的 DataX 能访问宿主机上的数据源
     */
    private String translateHost(String host) {
        if ("docker".equals(dataxMode)) {
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                return "host.docker.internal";
            }
        }
        return host;
    }

    /**
     * 生成 DataX JSON 配置文件（mysqlreader → hdfswriter）
     */
    public String generateJobJson(String mysqlHost, int mysqlPort, String mysqlUser, String mysqlPassword,
                                  String sourceDb, String sourceTable,
                                  String odsTable, List<ColumnInfo> columns) throws IOException {
        // 确保目录存在
        new File(jobDir).mkdirs();

        // Docker 模式下翻译地址
        String actualHost = translateHost(mysqlHost);

        JSONObject job = new JSONObject(true);
        JSONObject jobContent = new JSONObject(true);

        // === Reader: mysqlreader ===
        JSONObject reader = new JSONObject(true);
        reader.put("name", "mysqlreader");
        JSONObject readerParam = new JSONObject(true);

        readerParam.put("username", mysqlUser);
        readerParam.put("password", mysqlPassword);

        JSONArray columnArr = new JSONArray();
        for (ColumnInfo col : columns) {
            columnArr.add(col.getName());
        }
        readerParam.put("column", columnArr);

        JSONArray connArr = new JSONArray();
        JSONObject conn = new JSONObject(true);
        conn.put("jdbcUrl", new JSONArray() {{
            add("jdbc:mysql://" + actualHost + ":" + mysqlPort + "/" + sourceDb
                    + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false");
        }});
        conn.put("table", new JSONArray() {{ add(sourceTable); }});
        connArr.add(conn);
        readerParam.put("connection", connArr);

        reader.put("parameter", readerParam);

        // === Writer: hdfswriter ===
        JSONObject writer = new JSONObject(true);
        writer.put("name", "hdfswriter");
        JSONObject writerParam = new JSONObject(true);

        String today = java.time.LocalDate.now().minusDays(1).toString();
        writerParam.put("defaultFS", hiveDefaultFs);
        writerParam.put("fileType", "orc");
        writerParam.put("path", hiveWarehouse + "/ods.db/" + odsTable + "/dt=" + today);
        writerParam.put("fileName", odsTable);
        writerParam.put("writeMode", "truncate");
        writerParam.put("fieldDelimiter", "\t");
        writerParam.put("compress", "SNAPPY");

        JSONArray writerColumns = new JSONArray();
        for (ColumnInfo col : columns) {
            JSONObject wc = new JSONObject(true);
            wc.put("name", col.getName().toLowerCase());
            wc.put("type", "string");
            writerColumns.add(wc);
        }
        writerParam.put("column", writerColumns);
        writer.put("parameter", writerParam);

        // === Assemble ===
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

        // 写入临时文件供 DataX 执行
        new File(jobDir).mkdirs();
        String filePath = jobDir + "/" + odsTable + ".json";
        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(jsonStr);
        }
        log.info("DataX JSON 已生成: {}", filePath);
        return filePath;
    }

    /**
     * 返回 DataX JSON 字符串（用于存入数据库）
     */
    public String generateJobJsonString(String mysqlHost, int mysqlPort, String mysqlUser, String mysqlPassword,
                                        String sourceDb, String sourceTable,
                                        String odsTable, List<ColumnInfo> columns) {
        try {
            String filePath = generateJobJson(mysqlHost, mysqlPort, mysqlUser, mysqlPassword,
                    sourceDb, sourceTable, odsTable, columns);
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        } catch (Exception e) {
            log.error("生成 DataX JSON 失败", e);
            return null;
        }
    }

    /**
     * 执行 DataX 同步任务
     * local 模式：本地 Java 调用
     * docker 模式：docker exec 容器内执行
     */
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
        log.info("执行 DataX (local): {}", jobFilePath);
        return ProcessUtil.exec(cmd, 600);
    }

    private ProcessUtil.ExecResult runJobDocker(String jobFilePath) throws Exception {
        // Docker 模式：先把 job 文件复制到容器，再在容器内执行
        String containerName = "datanote-datax";
        String containerJobPath = "/tmp/datax_jobs/" + new File(jobFilePath).getName();

        // 复制 job 文件到容器
        String[] cpCmd = {"docker", "cp", jobFilePath, containerName + ":" + containerJobPath};
        ProcessUtil.exec(cpCmd, 30);

        // 在容器内执行 DataX
        String[] cmd = {
                "docker", "exec", containerName,
                "python", "/opt/datax/bin/datax.py", containerJobPath
        };
        log.info("执行 DataX (docker): {} -> {}", jobFilePath, containerJobPath);
        return ProcessUtil.exec(cmd, 600);
    }

    /**
     * 从数据库存储的 JSON 字符串执行 DataX：写临时文件 → 执行 → 删临时文件
     */
    public ProcessUtil.ExecResult runJobFromJson(String dataxJsonContent, String taskName) throws Exception {
        // Docker 模式下翻译 JSON 中的地址
        if ("docker".equals(dataxMode)) {
            dataxJsonContent = dataxJsonContent
                    .replace("127.0.0.1", "host.docker.internal")
                    .replace("localhost", "host.docker.internal");
        }

        new File(jobDir).mkdirs();
        String tmpFile = jobDir + "/" + taskName + "_" + System.currentTimeMillis() + ".json";
        try {
            try (FileWriter fw = new FileWriter(tmpFile)) {
                fw.write(dataxJsonContent);
            }
            return runJob(tmpFile);
        } finally {
            try { new File(tmpFile).delete(); } catch (Exception ignored) {}
        }
    }
}
