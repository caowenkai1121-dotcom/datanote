package com.datanote.sync.schema;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M7：schema 漂移分级——新增列安全，删列/改类型/改主键危险。 */
public class SchemaDriftClassifierTest {

    private Map<String, String> cols(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void noChange() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "int", "name", "varchar(50)"), Arrays.asList("id"),
                cols("id", "int", "name", "varchar(50)"), Arrays.asList("id"));
        assertFalse(r.dangerous());
        assertTrue(r.added.isEmpty());
    }

    @Test
    void addedColumnSafe() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "int"), Arrays.asList("id"),
                cols("id", "int", "age", "int"), Arrays.asList("id"));
        assertFalse(r.dangerous(), "仅新增列应安全");
        assertEquals(Arrays.asList("age"), r.added);
    }

    @Test
    void droppedColumnDangerous() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "int", "name", "varchar(50)"), Arrays.asList("id"),
                cols("id", "int"), Arrays.asList("id"));
        assertTrue(r.dangerous());
        assertEquals(Arrays.asList("name"), r.dropped);
    }

    @Test
    void typeChangeDangerous() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "int", "amount", "decimal(10,2)"), Arrays.asList("id"),
                cols("id", "int", "amount", "varchar(20)"), Arrays.asList("id"));
        assertTrue(r.dangerous());
        assertEquals(Arrays.asList("amount"), r.typeChanged);
    }

    @Test
    void pkChangeDangerous() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "int", "code", "varchar(20)"), Arrays.asList("id"),
                cols("id", "int", "code", "varchar(20)"), Arrays.asList("id", "code"));
        assertTrue(r.dangerous());
        assertTrue(r.pkChanged);
    }

    @Test
    void typeNormalizationIgnoresCaseAndSpace() {
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(
                cols("id", "INT", "name", "VARCHAR(50)"), Arrays.asList("id"),
                cols("id", "int ", "name", "varchar(50)"), Arrays.asList("id"));
        assertFalse(r.dangerous(), "大小写/空白差异不算漂移");
    }
}
