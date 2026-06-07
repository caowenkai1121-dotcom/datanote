package com.datanote.domain.integration.util;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class ChecksumSqlBuilderTest {
    @Test void rowHashSinglePk() {
        String s = ChecksumSqlBuilder.buildRowHashSql("db","t", Arrays.asList("id","name"), Arrays.asList("id"));
        assertEquals("SELECT `id`, MD5(CONCAT_WS('#', IFNULL(`id`,'\\0'), IFNULL(`name`,'\\0'))) AS __h FROM `db`.`t`", s);
    }
    @Test void rowHashMultiPk() {
        String s = ChecksumSqlBuilder.buildRowHashSql("db","t", Arrays.asList("a","b","c"), Arrays.asList("a","b"));
        assertEquals("SELECT `a`, `b`, MD5(CONCAT_WS('#', IFNULL(`a`,'\\0'), IFNULL(`b`,'\\0'), IFNULL(`c`,'\\0'))) AS __h FROM `db`.`t`", s);
    }
}
