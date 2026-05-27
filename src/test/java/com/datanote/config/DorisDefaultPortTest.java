package com.datanote.config;

import com.datanote.service.DataxService;
import com.datanote.service.DolphinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisDefaultPortTest {

    @Test
    void javaDefaultsUseDorisQueryPort9030() throws Exception {
        assertEquals("${doris.query-port:9030}", valueAnnotation(DataxService.class, "dorisQueryPort"));
        assertEquals("${doris.query-port:9030}", valueAnnotation(DolphinService.class, "dorisQueryPort"));
    }

    @Test
    void packagedYamlDefaultsUseDorisQueryPort9030() throws Exception {
        assertYamlUses9030(Path.of("src/main/resources/application.yml"));
        assertYamlUses9030(Path.of("src/main/resources/application-example.yml"));
    }

    private String valueAnnotation(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        return field.getAnnotation(Value.class).value();
    }

    private void assertYamlUses9030(Path path) throws Exception {
        String yaml = Files.readString(path);
        assertTrue(yaml.contains("DORIS_QUERY_PORT:9030") || yaml.contains("query-port: 9030"));
        assertTrue(yaml.contains("38.76.183.50:9030/ods"));
        assertFalse(yaml.contains("38.76.183.50:903/ods"));
        assertFalse(yaml.contains("DORIS_QUERY_PORT:903}"));
        assertFalse(Pattern.compile("(?m)^\\s*query-port:\\s*903\\s*$").matcher(yaml).find());
    }
}
