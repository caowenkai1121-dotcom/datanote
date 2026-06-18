package com.datanote.domain.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HiveDdlController.isDangerousSql 破坏性语句识别单测(P1-04)。 */
class HiveDdlDangerSqlTest {

    @Test
    void safeStatements_notDangerous() {
        assertFalse(HiveDdlController.isDangerousSql("SELECT * FROM t"));
        assertFalse(HiveDdlController.isDangerousSql("WITH a AS (SELECT 1) SELECT * FROM a"));
        assertFalse(HiveDdlController.isDangerousSql("CREATE TABLE x (id INT)"));
        assertFalse(HiveDdlController.isDangerousSql("INSERT INTO x VALUES (1)"));
        assertFalse(HiveDdlController.isDangerousSql("ALTER TABLE x ADD COLUMN c INT"));
        assertFalse(HiveDdlController.isDangerousSql(null));
    }

    @Test
    void destructiveStatements_areDangerous() {
        assertTrue(HiveDdlController.isDangerousSql("DROP TABLE x"));
        assertTrue(HiveDdlController.isDangerousSql("drop database d"));
        assertTrue(HiveDdlController.isDangerousSql("TRUNCATE TABLE x"));
        assertTrue(HiveDdlController.isDangerousSql("GRANT ALL ON *.* TO 'u'"));
        assertTrue(HiveDdlController.isDangerousSql("REVOKE SELECT ON db.t FROM 'u'"));
        assertTrue(HiveDdlController.isDangerousSql("ALTER TABLE x DROP COLUMN c"));
    }

    @Test
    void commentBypass_andMultiStatement_stillCaught() {
        assertTrue(HiveDdlController.isDangerousSql("-- harmless\nDROP TABLE x"));
        assertTrue(HiveDdlController.isDangerousSql("/* c */ TRUNCATE TABLE x"));
        assertTrue(HiveDdlController.isDangerousSql("SELECT 1; DROP TABLE x"));
    }
}
