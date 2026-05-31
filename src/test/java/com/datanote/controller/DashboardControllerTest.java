package com.datanote.controller;

import com.datanote.model.R;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardControllerTest {

    @Test
    void servicesUsesConfiguredMysqlPortFromDatasourceUrl() {
        DashboardController controller = new DashboardController(null, null, null, null);
        ReflectionTestUtils.setField(controller, "dataSourceUrl",
                "jdbc:mysql://127.0.0.1:3307/datanote?useUnicode=true");

        R<List<Map<String, Object>>> response = controller.services();

        Map<String, Object> mysql = response.getData().stream()
                .filter(item -> "MySQL".equals(item.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MySQL service entry not found"));
        assertEquals(3307, mysql.get("port"));
    }
}
