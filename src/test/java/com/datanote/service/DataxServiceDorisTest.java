package com.datanote.service;

import com.datanote.domain.integration.DataxService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.datanote.domain.metadata.model.ColumnInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DataxServiceDorisTest {

    @Test
    void generateJobJson_writesToDorisThroughMysqlProtocol() throws Exception {
        Path jobDir = Files.createTempDirectory("datanote-datax");
        DataxService service = new DataxService();
        ReflectionTestUtils.setField(service, "jobDir", jobDir.toString());
        ReflectionTestUtils.setField(service, "dataxMode", "local");
        ReflectionTestUtils.setField(service, "dorisHost", "38.76.183.50");
        ReflectionTestUtils.setField(service, "dorisQueryPort", 9030);
        ReflectionTestUtils.setField(service, "dorisDatabase", "ods");
        ReflectionTestUtils.setField(service, "dorisUsername", "root");
        ReflectionTestUtils.setField(service, "dorisPassword", "123456");

        String jobPath = service.generateJobJson("127.0.0.1", 3306, "src_user", "src_pass",
                "mall", "orders", "ods_mall_orders_df",
                Arrays.asList(column("id"), column("amount")),
                "2026-05-24");

        String json = new String(Files.readAllBytes(Paths.get(jobPath)));

        assertTrue(json.contains("\"name\":\"mysqlreader\""));
        assertTrue(json.contains("SELECT '2026-05-24' AS `dt`, `id` AS `id`, `amount` AS `amount` FROM `mall`.`orders`"));
        assertTrue(json.contains("\"name\":\"mysqlwriter\""));
        assertTrue(json.contains("jdbc:mysql://127.0.0.1:3306/mall?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"));
        assertTrue(json.contains("jdbc:mysql://38.76.183.50:9030/ods"));
        assertTrue(json.contains("\"username\":\"root\""));
        assertTrue(json.contains("\"password\":\"123456\""));
        assertTrue(json.contains("DELETE FROM `ods`.`ods_mall_orders_df` WHERE `dt` = '2026-05-24'"));
        assertFalse(json.contains("hdfswriter"));
        assertFalse(json.contains("hdfs://"));

        JSONObject root = JSON.parseObject(json);
        Object writerJdbcUrl = root.getJSONObject("job")
                .getJSONArray("content").getJSONObject(0)
                .getJSONObject("writer")
                .getJSONObject("parameter")
                .getJSONArray("connection").getJSONObject(0)
                .get("jdbcUrl");
        assertTrue(writerJdbcUrl instanceof String);
    }

    private ColumnInfo column(String name) {
        ColumnInfo column = new ColumnInfo();
        column.setName(name);
        return column;
    }
}
