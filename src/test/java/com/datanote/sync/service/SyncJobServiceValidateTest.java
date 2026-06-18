package com.datanote.domain.integration.service;

import com.datanote.domain.integration.model.DnSyncJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * save 服务端校验(B8)纯逻辑单测：validate 仅依赖 job 对象，不触达 mapper，可传 null 构造。
 */
class SyncJobServiceValidateTest {

    private final SyncJobService svc = new SyncJobService(null, null, null, null, null, null, null, null, null, null, null);

    private static DnSyncJob base() {
        DnSyncJob j = new DnSyncJob();
        j.setJobName("t");
        j.setSourceDsId(1L);
        j.setTargetDsId(2L);
        j.setSyncMode("FULL");
        return j;
    }

    @Test
    void rejectsBlankName() {
        DnSyncJob j = base();
        j.setJobName("  ");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsMissingDatasource() {
        DnSyncJob j = base();
        j.setTargetDsId(null);
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsBadSyncMode() {
        DnSyncJob j = base();
        j.setSyncMode("WHATEVER");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsBadWriteMode() {
        DnSyncJob j = base();
        j.setWriteMode("MERGE");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsBadCron() {
        DnSyncJob j = base();
        j.setScheduleCron("not a cron");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsBadTableConfigJson() {
        DnSyncJob j = base();
        j.setTableConfig("{not-json");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsDangerousJobLevelPreSql() {
        DnSyncJob j = base();
        j.setPreSql("DROP TABLE ods.customer");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsDangerousTableLevelPostSql() {
        DnSyncJob j = base();
        j.setTableConfig("[{\"sourceTable\":\"s\",\"targetTable\":\"t\",\"postSql\":\"DELETE FROM ods.t\"}]");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void rejectsIncrementalMissingField() {
        DnSyncJob j = base();
        j.setSyncMode("INCREMENTAL");
        j.setTableConfig("[{\"sourceTable\":\"s\",\"targetTable\":\"t\"}]");
        assertThrows(IllegalArgumentException.class, () -> svc.validate(j));
    }

    @Test
    void acceptsValidFull() {
        DnSyncJob j = base();
        j.setWriteMode("UPSERT");
        j.setScheduleCron("0 0 2 * * *");
        j.setTableConfig("[{\"sourceTable\":\"s\",\"targetTable\":\"t\"}]");
        assertDoesNotThrow(() -> svc.validate(j));
    }

    @Test
    void acceptsValidIncrementalWithField() {
        DnSyncJob j = base();
        j.setSyncMode("INCREMENTAL");
        j.setTableConfig("[{\"sourceTable\":\"s\",\"targetTable\":\"t\",\"incrementalField\":\"updated_at\"}]");
        assertDoesNotThrow(() -> svc.validate(j));
    }
}
