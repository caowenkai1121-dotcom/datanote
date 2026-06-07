package com.datanote.domain.consumption;

import com.datanote.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据集只读校验纯函数单测（R13）：仅允许 SELECT/WITH，拒绝写操作，去注释后判定。
 */
class DatasetServiceTest {

    @Test
    void allowsSelectAndWith() {
        assertDoesNotThrow(() -> DatasetService.validateReadOnly("SELECT 1"));
        assertDoesNotThrow(() -> DatasetService.validateReadOnly("  select * from t "));
        assertDoesNotThrow(() -> DatasetService.validateReadOnly("WITH a AS (SELECT 1) SELECT * FROM a"));
    }

    @Test
    void rejectsWriteOps() {
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly("DELETE FROM t"));
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly("DROP TABLE t"));
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly("UPDATE t SET x=1"));
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly("INSERT INTO t VALUES(1)"));
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly(""));
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly(null));
    }

    @Test
    void stripsCommentsBeforeJudging() {
        assertDoesNotThrow(() -> DatasetService.validateReadOnly("-- 注释\nSELECT 1"));
        assertDoesNotThrow(() -> DatasetService.validateReadOnly("/* 块注释 */ SELECT 1"));
        // 注释伪装的写操作仍被识别(注释被去掉后是 DELETE)
        assertThrows(BusinessException.class, () -> DatasetService.validateReadOnly("-- SELECT\nDELETE FROM t"));
    }
}
