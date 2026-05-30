package com.datanote.sync.engine.cdc;

import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.cdc.JdbcSchemaHistory;
import com.datanote.sync.dto.TableSyncConfig;
import io.debezium.config.Configuration;
import io.debezium.relational.history.DatabaseHistoryListener;
import io.debezium.relational.history.HistoryRecordComparator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdcSyncEngineConfigTest {

    @Test
    void buildPropsPassesJobIdToDebeziumDatabaseHistory() throws Exception {
        CdcSyncEngine engine = newEngine(42L);

        Method buildProps = CdcSyncEngine.class.getDeclaredMethod("buildProps");
        buildProps.setAccessible(true);
        Properties props = (Properties) buildProps.invoke(engine);

        assertEquals("42", props.getProperty(CdcSyncEngine.JOB_ID_CONFIG));
        assertEquals("42", props.getProperty("database.history." + CdcSyncEngine.JOB_ID_CONFIG));
    }

    @Test
    void schemaHistoryAcceptsDatabaseHistoryScopedJobId() {
        JdbcSchemaHistory history = new JdbcSchemaHistory();
        Configuration config = Configuration.create()
                .with("database.history." + CdcSyncEngine.JOB_ID_CONFIG, "42")
                .build();

        history.configure(config, HistoryRecordComparator.INSTANCE, DatabaseHistoryListener.NOOP, false);
    }

    private static CdcSyncEngine newEngine(Long jobId) {
        DnSyncJob job = new DnSyncJob();
        job.setId(jobId);
        job.setSourceDb("src_db");
        job.setTargetDb("dst_db");
        job.setTargetDsId(2L);

        DnDatasource source = new DnDatasource();
        source.setHost("127.0.0.1");
        source.setPort(3306);
        source.setUsername("root");
        source.setPassword("plain-password");

        TableSyncConfig table = new TableSyncConfig();
        table.setSourceTable("t_order");
        table.setTargetTable("t_order");

        return new CdcSyncEngine(job, source, Collections.singletonList(table),
                null, null, "1234567890123456", "DORIS", 5400L, 2048, 8192, 500, 30000, null);
    }
}
