package com.datanote.domain.integration.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SignalSqlBuilderTest {

    @Test
    void executeSnapshot() {
        String s = SignalSqlBuilder.executeSnapshotData(Arrays.asList("db.t1", "db.t2"));
        assertEquals("{\"data-collections\": [\"db.t1\",\"db.t2\"], \"type\": \"INCREMENTAL\"}", s);
    }

    @Test
    void insertSql() {
        assertEquals("INSERT INTO `srcdb`.`dn_cdc_signal` (`id`,`type`,`data`) VALUES (?,?,?)",
                SignalSqlBuilder.insertSql("srcdb"));
    }
}
